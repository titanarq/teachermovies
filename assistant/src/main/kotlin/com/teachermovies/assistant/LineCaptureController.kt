package com.teachermovies.assistant

import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.player.api.Player
import com.teachermovies.player.api.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A line the viewer froze with [LineCaptureController.capture].
 *
 * @property cue the subtitle cue that was being spoken (or had just been spoken).
 * @property capturedAtMs the playback position at the moment of the capture; replay and dismiss
 *   always return the player here.
 */
data class CapturedLine(
    val cue: SubtitleCue,
    val capturedAtMs: Long,
)

/** Outcome of [LineCaptureController.capture]. */
sealed interface CaptureResult {
    /** A line was resolved and is now on [LineCaptureController.captured]. */
    data object Captured : CaptureResult

    /** No cue at (or shortly before) the current position, or no track loaded. */
    data object NoLine : CaptureResult
}

/**
 * "What did they say?": pauses the movie on the line being spoken ([capture]), plays that line's
 * original audio fragment again as many times as asked ([replay]), and hands the movie back
 * ([dismiss]).
 *
 * Playback is driven only through the [Player] interface. A replay plays
 * `[cue.startMs - preRollMs, cue.endMs + tailMs]` and is ended by a single watcher coroutine on
 * [scope], which pauses the player and seeks back to [CapturedLine.capturedAtMs]; at most one
 * such watcher exists at any time.
 *
 * OK pressed repeatedly steps back through the track (#339): the Nth [replay] of a run -- calls no
 * more than [REPLAY_BACK_WINDOW_MS] apart, the first one after a [capture] starting a run at
 * N = 1 -- plays the cue N - 1 lines before the captured one, clamped at the track's first cue, and
 * [linesBack] says how far back the cue being played is. [clock] supplies the `now` the window is
 * measured with.
 */
class LineCaptureController(
    private val player: Player,
    private val engine: SubtitleEngine,
    private val scope: CoroutineScope,
    private val preRollMs: Long = 300,
    private val tailMs: Long = 200,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val mutableCaptured = MutableStateFlow<CapturedLine?>(null)
    val captured: StateFlow<CapturedLine?> = mutableCaptured.asStateFlow()

    private val mutableReplaying = MutableStateFlow(false)
    val replaying: StateFlow<Boolean> = mutableReplaying.asStateFlow()

    private val mutableLinesBack = MutableStateFlow(0)

    /**
     * How many lines before the captured one the replay in progress (or the last one) played: 0 for
     * the captured line itself. Reset by [capture] and [dismiss].
     */
    val linesBack: StateFlow<Int> = mutableLinesBack.asStateFlow()

    private var watcher: Job? = null

    /** OK presses in a row inside [REPLAY_BACK_WINDOW_MS]; 0 while no run is open. */
    private var replayRun = 0
    private var lastReplayAtMs = 0L

    /**
     * Pauses the player, then resolves the line at the paused position through
     * [SubtitleEngine.cueForCapture]. The player stays paused whatever the result.
     */
    fun capture(): CaptureResult {
        player.pause()
        replayRun = 0
        mutableLinesBack.value = 0
        val positionMs = player.positionMs.value
        val cue = engine.cueForCapture(positionMs) ?: return CaptureResult.NoLine
        mutableCaptured.value = CapturedLine(cue, capturedAtMs = positionMs)
        return CaptureResult.Captured
    }

    /**
     * Plays the fragment of the line the current run of OK presses points at -- the captured line
     * itself on the first press, the one before it on the second, and so on, clamped at the track's
     * first cue -- from its beginning, restarting it when a replay is already running. Returns
     * `false`, doing nothing, when nothing is captured.
     */
    fun replay(): Boolean {
        val line = mutableCaptured.value ?: return false
        cancelWatcher()

        val now = clock()
        replayRun = if (replayRun > 0 && now - lastReplayAtMs <= REPLAY_BACK_WINDOW_MS) replayRun + 1 else 1
        lastReplayAtMs = now
        val stepped = engine.cueLinesBefore(line.cue, replayRun - 1)
        val cue = stepped?.cue ?: line.cue
        mutableLinesBack.value = stepped?.linesBack ?: 0

        val endMs = cue.endMs + tailMs
        player.seekTo(maxOf(0L, cue.startMs - preRollMs))
        player.play()
        mutableReplaying.value = true

        watcher =
            scope.launch {
                combine(player.positionMs, player.state) { position, state ->
                    position >= endMs || state is PlayerState.Ended || state is PlayerState.Error
                }.first { finished -> finished }
                player.pause()
                player.seekTo(line.capturedAtMs)
                mutableReplaying.value = false
            }
        return true
    }

    /**
     * Cancels any running replay, clears the capture and returns the player to the captured
     * position, resuming playback only when [resume]. A no-op when nothing is captured.
     */
    fun dismiss(resume: Boolean = true) {
        val line = mutableCaptured.value ?: return
        cancelWatcher()
        mutableReplaying.value = false
        mutableCaptured.value = null
        replayRun = 0
        mutableLinesBack.value = 0
        player.seekTo(line.capturedAtMs)
        if (resume) {
            player.play()
        } else {
            // A replay may have been playing: leave the movie paused either way.
            player.pause()
        }
    }

    private fun cancelWatcher() {
        watcher?.cancel()
        watcher = null
    }

    companion object {
        /** How far apart two [replay] calls can be and still count as one run of OK presses (#339). */
        const val REPLAY_BACK_WINDOW_MS = 1_500L
    }
}
