package com.teachermovies.assistant

import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.core.log.AppLog
import com.teachermovies.player.api.Player
import com.teachermovies.player.api.PlayerState
import com.teachermovies.player.session.SubtitleSaveGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/** Which line the assistant draws on screen, and whether it draws one at all. */
enum class SubtitleDisplay {
    /** Nothing is drawn; the movie plays with the assistant's overlay hidden. */
    OFF,

    /** The English line, from the track [SubtitleEngine] has loaded. */
    ENGLISH,

    /** The Spanish line, from the [SpanishTextSource]. */
    SPANISH,
}

/** What a rewind run has to put back: where it started and the subtitle selection it replaced. */
data class RewindSnapshot(
    /**
     * Where the run ends and the subtitle comes back: the end of the phrase in progress at the first
     * press, or the press position when it fell in a gap. Fixed once, by the first press.
     */
    val originMs: Long,
    /** The subtitle track selected before the first press; null = no subtitle on screen. */
    val previousSubtitleId: String?,
)

/** The lifecycle of the player-side subtitle of a phrase rewind (#358). */
sealed interface RewindSubtitleState {
    /** No run: the player's subtitle selection is the viewer's. */
    data object Idle : RewindSubtitleState

    /**
     * A run has started and its seek is issued or still to come, not confirmed yet; nothing is
     * ended by a position reported meanwhile. Also the state of a run whose language has no
     * subtitle to show ([TempSubtitleResult.Unavailable]).
     */
    data class Rewinding(
        val snapshot: RewindSnapshot,
        val language: SubtitleDisplay,
    ) : RewindSubtitleState

    /** The seek is confirmed and temporary track [trackId] is on the player until [RewindSnapshot.originMs]. */
    data class PlayingWithTempSubs(
        val snapshot: RewindSnapshot,
        val language: SubtitleDisplay,
        val trackId: String,
    ) : RewindSubtitleState

    /** The run is over and the snapshot's subtitle selection is being put back. */
    data class Restoring(
        val snapshot: RewindSnapshot,
    ) : RewindSubtitleState
}

/**
 * LEFT pressed N times: go back N subtitle phrases, show the line being replayed, and put the
 * subtitle state back once playback returns to where the run started (#343, ADR-0005's D-pad
 * interaction model).
 *
 * Presses less than [REWIND_GROUP_WINDOW_MS] apart -- the gap measured with [clock] -- form one
 * group of [Group.count] presses, and nothing is seeked while they keep arriving. Only once the
 * window has closed does the controller seek to the cue [Group.count] - 1 lines before the one at
 * (or just before) the position of the group's first press -- resolved through
 * [SubtitleEngine.cueForCapture] and [SubtitleEngine.cueLinesBefore], clamped at the track's first
 * cue -- and play from there, from a playing or a paused player alike.
 *
 * A press paints nothing and never pauses: the movie keeps playing and [display] and [displayText]
 * stay as they were while the window is open, the last press's language only being remembered. When
 * the window closes [display] takes that language, and [displayText] is the line of that language at
 * the current position: English from the loaded track, Spanish from [spanish], `null` when the chosen
 * source has nothing there. The run's return point -- its origin -- is fixed by the first press: the
 * end of the phrase in progress (so the phrase just heard is replayed whole), or the press position
 * when it fell in a gap. Once playback reaches it, [display] goes back to the value it had before the
 * run and nothing is tracked any more. A group pressed again while rewound, before that point,
 * rewinds from where playback is but keeps the original origin and the original display to restore.
 *
 * A press is never dropped silently (#370). Every end of a run -- origin reached, [cancel],
 * [onUserSeek], the player ending or erroring, the viewer picking a track, no cue under the first
 * press -- goes through one path that logs `phrase rewind: end reason=<reason>`, puts the snapshot's
 * subtitle and the saved display back, returns [subtitleState] to [RewindSubtitleState.Idle] and
 * stops the tracker and the track-selection job; no coroutine this controller starts outlives it.
 * A press whose window is still open when the run ends by itself (origin reached, `Ended`, a viewer
 * track pick) is kept: when its window closes a fresh run starts from that press, its snapshot taken
 * after the restore. A viewer's [cancel] or [onUserSeek] drops it, and a press the controller does
 * not act on (`OFF`, the player in error, no cue) is logged as `phrase rewind: press ignored` or
 * `press dropped` with a reason and a state, never subtitle text. A press while the player is
 * `Ended` starts a run normally; only a later `Ended`/`Error` ends it. Until a group's seek is issued
 * the previous seek's confirmation is void, so the old origin cannot end the run while a track is
 * still being selected. Playback is driven only through the [Player] interface.
 *
 * With [subtitles] (#358) the line is drawn by the player itself: at the seek the language's subtitle
 * becomes a temporary player track ([subtitleState]) and [displayText] is `null` while it shows, so
 * the line is not drawn twice. The first press stores a [RewindSnapshot]; later presses never
 * overwrite it. The run only ends at the origin once the seek is *confirmed*: a position in
 * `[target - SEEK_TOLERANCE_MS, origin)` reported at least [SEEK_CONFIRM_MS] after the seek -- libVLC
 * delivers positions of the old time after `setTime`, which are ignored until then. Ending, however
 * it happens, selects the snapshot's subtitle again while playing, never pausing. Without
 * [subtitles] the overlay line is all there is.
 */
class PhraseRewindController(
    private val player: Player,
    private val engine: SubtitleEngine,
    private val spanish: SpanishTextSource,
    private val scope: CoroutineScope,
    private val preRollMs: Long = 300,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val subtitles: RewindSubtitleSession? = null,
) {
    private val mutableSubtitleState = MutableStateFlow<RewindSubtitleState>(RewindSubtitleState.Idle)

    /** Where the player-side subtitle of the rewind is; every change is logged once. */
    val subtitleState: StateFlow<RewindSubtitleState> = mutableSubtitleState.asStateFlow()

    private val mutableUnavailable = MutableSharedFlow<SubtitleDisplay>(extraBufferCapacity = 1)

    /** Emits the language of a run that found no subtitle to show ([TempSubtitleResult.Unavailable]). */
    val unavailable: SharedFlow<SubtitleDisplay> = mutableUnavailable.asSharedFlow()

    private var applyJob: Job? = null

    private val mutableDisplay = MutableStateFlow(SubtitleDisplay.OFF)
    val display: StateFlow<SubtitleDisplay> = mutableDisplay.asStateFlow()

    private val mutableDisplayText = MutableStateFlow<String?>(null)

    /** The line [display] names at the current playback position, `null` for [SubtitleDisplay.OFF]. */
    val displayText: StateFlow<String?> = mutableDisplayText.asStateFlow()

    /** The run being tracked, or `null` while the assistant shows nothing of its own. */
    private var rewind: Rewind? = null

    /** The group of presses still open, or `null` once its window has closed. */
    private var group: Group? = null
    private var settleJob: Job? = null
    private var tracker: Job? = null

    /**
     * One more rewind press, asking for the [language] line to be shown while it replays.
     *
     * A press less than [REWIND_GROUP_WINDOW_MS] after the previous one joins its group and steps
     * the rewind one more phrase back; a later one opens a group of its own at the current position,
     * joining the run in progress -- its return point and the display to restore unchanged -- or
     * starting one when nothing is being tracked. [SubtitleDisplay.OFF] is not a press the remote
     * can make and is ignored.
     */
    fun press(language: SubtitleDisplay) {
        if (language == SubtitleDisplay.OFF) {
            logIgnored("off")
            return
        }
        if (player.state.value is PlayerState.Error) {
            logIgnored("player-error")
            return
        }
        val now = clock()
        val positionMs = player.positionMs.value
        val open = group
        if (open != null && now - open.lastPressAtMs < REWIND_GROUP_WINDOW_MS) {
            open.count += 1
            open.lastPressAtMs = now
            open.language = language
        } else {
            group = Group(firstPressPositionMs = positionMs, lastPressAtMs = now, language = language)
            if (rewind == null) startRun(originFor(positionMs), language)
        }
        // Nothing is painted and nothing is paused here: the language is applied by settle().
        restartSettle()
    }

    /** Drops the run in progress, if any: the saved display and subtitle come back and nothing is seeked. */
    fun cancel() {
        endRun("cancel", keepGroup = false)
    }

    /** The viewer sought by hand: a run in progress ends at once, its subtitle selection restored. */
    fun onUserSeek() {
        endRun("user-seek", keepGroup = false)
    }

    /** Starts the temporary-subtitle session of [mediaFile]; null when the movie is left. */
    fun setMovie(mediaFile: File?) {
        subtitles?.setMovie(mediaFile)
    }

    /** Wires what keeps a temporary track out of the viewer's persisted choice. */
    fun attachSaveGuard(guard: SubtitleSaveGuard?) {
        subtitles?.attachSaveGuard(guard)
    }

    /**
     * Where a run started at [positionMs] ends: the end of the phrase in progress, so the phrase just
     * heard is replayed whole, or [positionMs] itself when it falls in a gap between phrases.
     */
    private fun originFor(positionMs: Long): Long {
        val cue = engine.cueForCapture(positionMs)
        return if (cue != null && cue.startMs <= positionMs && positionMs < cue.endMs) cue.endMs else positionMs
    }

    private fun startRun(
        returnPointMs: Long,
        language: SubtitleDisplay,
    ): Rewind {
        val snapshot = RewindSnapshot(returnPointMs, player.selectedSubtitleId.value)
        val started = Rewind(snapshot = snapshot, savedDisplay = mutableDisplay.value)
        // A press made while the video is over starts a run: only a later Ended/Error ends it.
        started.terminalAtStart = isTerminal(player.state.value)
        rewind = started
        setSubtitleState(RewindSubtitleState.Rewinding(snapshot, language))
        tracker =
            scope.launch {
                val reason = awaitEnd(started)
                // A press whose window is still open outlives the run: settle() starts a new one. After
                // an error no seek is possible, so there the press is dropped (and logged).
                endRun(reason, keepGroup = reason != "player-error")
            }
        return started
    }

    /** Publishes the line at every change and returns why [started] is over, once it is. */
    private suspend fun awaitEnd(started: Rewind): String =
        combine(
            player.positionMs,
            engine.currentSubtitle,
            player.state,
            subtitles?.temporaryId ?: NO_TEMPORARY,
        ) { position, english, state, temporary ->
            publish(position, english)
            if (!isTerminal(state)) started.terminalAtStart = false
            val viewerPicked =
                started.tempTrackId != null && !started.switching && temporary != started.tempTrackId
            when {
                state is PlayerState.Error && !started.terminalAtStart -> "player-error"

                state is PlayerState.Ended && !started.terminalAtStart -> "player-ended"

                // The viewer picked a track: it wins and the run has nothing left to restore.
                viewerPicked -> "viewer-track"

                atOrigin(started, position) -> "origin"

                else -> null
            }
        }.first { it != null } ?: "tracker"

    private fun isTerminal(state: PlayerState): Boolean = state is PlayerState.Ended || state is PlayerState.Error

    /**
     * Whether playback is back at the run's origin. Positions before the seek is confirmed are the
     * old time libVLC may still report, and never end the run.
     */
    private fun atOrigin(
        run: Rewind,
        position: Long,
    ): Boolean {
        if (!run.rewound) return false
        val origin = run.snapshot.originMs
        if (!run.confirmed) {
            val threshold = minOf(SEEK_CONFIRM_MS, (origin - run.targetMs) / 2)
            val inRange = position >= run.targetMs - SEEK_TOLERANCE_MS && position < origin
            if (inRange && clock() - run.seekAtMs >= threshold) {
                run.confirmed = true
                val state = mutableSubtitleState.value
                val track = run.tempTrackId
                if (track != null && state is RewindSubtitleState.Rewinding) {
                    setSubtitleState(RewindSubtitleState.PlayingWithTempSubs(run.snapshot, state.language, track))
                }
            }
        }
        return run.confirmed && position >= origin
    }

    private fun setSubtitleState(next: RewindSubtitleState) {
        val previous = mutableSubtitleState.value
        if (previous == next) return
        mutableSubtitleState.value = next
        AppLog.d(LOG_MODULE, "phrase rewind: $previous -> $next")
    }

    private fun restartSettle() {
        settleJob?.cancel()
        settleJob =
            scope.launch {
                delay(REWIND_GROUP_WINDOW_MS)
                settle()
            }
    }

    /** The group window has closed with no further press: rewind as far as the group asked. */
    private fun settle() {
        settleJob = null
        val presses = group ?: return
        group = null
        if (player.state.value is PlayerState.Error) {
            logIgnored("player-error")
            endRun("player-error", keepGroup = false)
            return
        }
        val cue = engine.cueForCapture(presses.firstPressPositionMs)
        if (cue == null) {
            // No line to rewind to: no track loaded, or a gap too long after the last one. Showing a
            // language with no line under it is worse than showing nothing, so the run ends here.
            logIgnored("no-cue")
            endRun("no-cue", keepGroup = false)
            return
        }
        val target = engine.cueLinesBefore(cue, presses.count - 1)?.cue ?: cue
        // The run the press joined may have ended while its window was open: start a fresh one from
        // the group's first press, its snapshot taken now that the viewer's subtitle is back.
        val run = rewind ?: startRun(originFor(presses.firstPressPositionMs), presses.language)
        val language = presses.language
        mutableDisplay.value = language
        val seekMs = maxOf(0L, target.startMs - preRollMs)
        // The old seek's confirmation says nothing about this one: until the new seek is issued the
        // origin must not end the run, however long the track selection below takes.
        run.rewound = false
        run.confirmed = false
        applyJob?.cancel()
        applyJob =
            scope.launch {
                // The track is chosen before the seek and play, so it is on screen from the first line.
                selectTemporary(run, language)
                run.rewound = true
                run.confirmed = false
                run.targetMs = seekMs
                run.seekAtMs = clock()
                setSubtitleState(RewindSubtitleState.Rewinding(run.snapshot, language))
                player.seekTo(seekMs)
                player.play()
                publishNow()
            }
    }

    /** Makes [language]'s subtitle the temporary player track of [run], switching a live one. */
    private suspend fun selectTemporary(
        run: Rewind,
        language: SubtitleDisplay,
    ) {
        val session = subtitles ?: return
        if (run.tempTrackId != null && run.tempLanguage == language) return
        run.switching = true
        try {
            when (val result = session.select(language, run.snapshot.previousSubtitleId)) {
                is TempSubtitleResult.Selected -> {
                    run.tempTrackId = result.trackId
                    run.tempLanguage = language
                }

                is TempSubtitleResult.Unavailable -> {
                    if (run.tempTrackId != null) {
                        // The other language's track must not keep showing under this press.
                        session.end(run.snapshot.previousSubtitleId)
                        run.tempTrackId = null
                        run.tempLanguage = null
                    }
                    mutableUnavailable.tryEmit(result.language)
                }
            }
        } finally {
            run.switching = false
        }
    }

    /**
     * The one way a run ends: [reason] is logged, the player's subtitle selection and the display
     * the run replaced come back, and the tracker and the track-selection job stop. A group still
     * open is dropped too, unless [keepGroup]: the run ending by itself must not eat a press made
     * inside its window, but a viewer's cancel or hand seek wins over it.
     */
    private fun endRun(
        reason: String,
        keepGroup: Boolean,
    ) {
        val pending = group
        if (!keepGroup && pending != null) {
            group = null
            settleJob?.cancel()
            settleJob = null
            AppLog.d(LOG_MODULE, "phrase rewind: press dropped reason=$reason state=${stateName()}")
        }
        val finished = rewind ?: return
        rewind = null
        applyJob?.cancel()
        applyJob = null
        val tracked = tracker
        tracker = null
        tracked?.cancel()
        AppLog.d(LOG_MODULE, "phrase rewind: end reason=$reason")
        if (finished.tempTrackId != null) {
            // Restoring is a live selection: the player keeps playing and is never paused for it.
            setSubtitleState(RewindSubtitleState.Restoring(finished.snapshot))
            subtitles?.end(finished.snapshot.previousSubtitleId)
        }
        mutableDisplay.value = finished.savedDisplay
        setSubtitleState(RewindSubtitleState.Idle)
        publishNow()
    }

    private fun logIgnored(reason: String) {
        AppLog.d(LOG_MODULE, "phrase rewind: press ignored reason=$reason state=${stateName()}")
    }

    private fun stateName(): String = mutableSubtitleState.value::class.simpleName ?: "?"

    private fun publishNow() {
        publish(player.positionMs.value, engine.currentSubtitle.value)
    }

    private fun publish(
        positionMs: Long,
        english: SubtitleCue?,
    ) {
        mutableDisplayText.value =
            when {
                // The player draws this line itself; a second copy would show twice.
                rewind?.tempTrackId != null -> null

                else -> overlayLine(positionMs, english)
            }
    }

    /** The engine's line trails a seek by a moment, so an English line the position is not in is not shown. */
    private fun overlayLine(
        positionMs: Long,
        english: SubtitleCue?,
    ): String? =
        when (mutableDisplay.value) {
            SubtitleDisplay.OFF -> null
            SubtitleDisplay.ENGLISH -> english?.takeIf { positionMs in it.startMs until it.endMs }?.text
            SubtitleDisplay.SPANISH -> spanish.textAt(positionMs)
        }

    /** One run of the rewind: what to restore once playback is back at the origin, and where it stands. */
    private class Rewind(
        val snapshot: RewindSnapshot,
        val savedDisplay: SubtitleDisplay,
    ) {
        /**
         * Whether the run has seeked yet. The return point is the position of its first press, so
         * watching for it before the rewind would end the run on the spot.
         */
        var rewound = false

        /** Where the last seek went, when (by the injected clock), and whether a position proved it. */
        var targetMs = 0L
        var seekAtMs = 0L
        var confirmed = false

        /** True while the player was already Ended/Error when the run began, until it plays again. */
        var terminalAtStart = false

        /** The temporary player track on screen and its language, null while there is none. */
        @Volatile var tempTrackId: String? = null
        var tempLanguage: SubtitleDisplay? = null

        /** True while a track is being selected, so the tracker does not read it as the viewer's pick. */
        @Volatile var switching = false
    }

    /** The presses of one group, still open while they arrive less than a window apart. */
    private class Group(
        val firstPressPositionMs: Long,
        var lastPressAtMs: Long,
        /** The language of the last press: the one applied when the window closes. */
        var language: SubtitleDisplay,
    ) {
        var count = 1
    }

    companion object {
        /** How far apart two presses can be and still rewind as one group (#343). */
        const val REWIND_GROUP_WINDOW_MS = 1_500L

        /** How long after the seek a position still in range proves it took effect (#358). */
        const val SEEK_CONFIRM_MS = 500L

        /** How far before the seek target a position still counts as the seek having landed. */
        const val SEEK_TOLERANCE_MS = 1_000L

        private const val LOG_MODULE = "assistant"
        private val NO_TEMPORARY = MutableStateFlow<String?>(null)
    }
}
