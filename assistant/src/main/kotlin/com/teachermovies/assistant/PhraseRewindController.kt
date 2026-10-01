package com.teachermovies.assistant

import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.player.api.Player
import com.teachermovies.player.api.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Which line the assistant draws on screen, and whether it draws one at all. */
enum class SubtitleDisplay {
    /** Nothing is drawn; the movie plays with the assistant's overlay hidden. */
    OFF,

    /** The English line, from the track [SubtitleEngine] has loaded. */
    ENGLISH,

    /** The Spanish line, from the [SpanishTextSource]. */
    SPANISH,
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
 * [display] says which language the assistant draws, from the group's first press on and with the
 * last press's language winning, and [displayText] the line of that language at the current
 * position: English from the loaded track, Spanish from [spanish], `null` when the chosen source has
 * nothing there. Once playback reaches the position of the run's first press -- its return point --
 * [display] goes back to the value it had before the run and nothing is tracked any more. A group
 * pressed again while rewound, before that point, rewinds from where playback is but keeps the
 * original return point and the original display to restore.
 *
 * The player ending or erroring, and [cancel], drop the run the same way; no coroutine this
 * controller starts outlives them. Playback is driven only through the [Player] interface.
 */
class PhraseRewindController(
    private val player: Player,
    private val engine: SubtitleEngine,
    private val spanish: SpanishTextSource,
    private val scope: CoroutineScope,
    private val preRollMs: Long = 300,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
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
        if (language == SubtitleDisplay.OFF) return
        val now = clock()
        val positionMs = player.positionMs.value
        val open = group
        if (open != null && now - open.lastPressAtMs < REWIND_GROUP_WINDOW_MS) {
            open.count += 1
            open.lastPressAtMs = now
        } else {
            group = Group(firstPressPositionMs = positionMs, lastPressAtMs = now)
            if (rewind == null) startRun(positionMs)
        }
        mutableDisplay.value = language
        publishNow()
        restartSettle()
    }

    /** Drops the run in progress, if any: the saved display comes back and nothing is seeked. */
    fun cancel() {
        dropRun()
    }

    private fun startRun(returnPointMs: Long) {
        val started = Rewind(returnPointMs = returnPointMs, savedDisplay = mutableDisplay.value)
        rewind = started
        tracker =
            scope.launch {
                combine(player.positionMs, engine.currentSubtitle, player.state) { position, english, state ->
                    publish(position, english)
                    state is PlayerState.Ended ||
                        state is PlayerState.Error ||
                        (started.rewound && position >= started.returnPointMs)
                }.first { finished -> finished }
                dropRun()
            }
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
        val cue = engine.cueForCapture(presses.firstPressPositionMs)
        if (cue == null) {
            // No line to rewind to: no track loaded, or a gap too long after the last one. Showing a
            // language with no line under it is worse than showing nothing, so the run ends here.
            dropRun()
            return
        }
        val target = engine.cueLinesBefore(cue, presses.count - 1)?.cue ?: cue
        rewind?.rewound = true
        player.seekTo(maxOf(0L, target.startMs - preRollMs))
        player.play()
        publishNow()
    }

    private fun dropRun() {
        val finished = rewind ?: return
        rewind = null
        group = null
        settleJob?.cancel()
        settleJob = null
        val tracked = tracker
        tracker = null
        tracked?.cancel()
        mutableDisplay.value = finished.savedDisplay
        publishNow()
    }

    private fun publishNow() {
        publish(player.positionMs.value, engine.currentSubtitle.value)
    }

    private fun publish(
        positionMs: Long,
        english: SubtitleCue?,
    ) {
        mutableDisplayText.value =
            when (mutableDisplay.value) {
                SubtitleDisplay.OFF -> null
                SubtitleDisplay.ENGLISH -> english?.text
                SubtitleDisplay.SPANISH -> spanish.textAt(positionMs)
            }
    }

    /** One run of the rewind: where playback has to return to, and what to restore once it does. */
    private class Rewind(
        val returnPointMs: Long,
        val savedDisplay: SubtitleDisplay,
    ) {
        /**
         * Whether the run has seeked yet. The return point is the position of its first press, so
         * watching for it before the rewind would end the run on the spot.
         */
        var rewound = false
    }

    /** The presses of one group, still open while they arrive less than a window apart. */
    private class Group(
        val firstPressPositionMs: Long,
        var lastPressAtMs: Long,
    ) {
        var count = 1
    }

    companion object {
        /** How far apart two presses can be and still rewind as one group (#343). */
        const val REWIND_GROUP_WINDOW_MS = 1_500L
    }
}
