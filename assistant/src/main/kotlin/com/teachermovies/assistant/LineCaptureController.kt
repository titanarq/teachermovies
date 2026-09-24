package com.teachermovies.assistant

import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.player.api.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 */
class LineCaptureController(
    private val player: Player,
    private val engine: SubtitleEngine,
    private val scope: CoroutineScope,
    private val preRollMs: Long = 300,
    private val tailMs: Long = 200,
) {
    private val mutableCaptured = MutableStateFlow<CapturedLine?>(null)
    val captured: StateFlow<CapturedLine?> = mutableCaptured.asStateFlow()

    private val mutableReplaying = MutableStateFlow(false)
    val replaying: StateFlow<Boolean> = mutableReplaying.asStateFlow()

    private var watcher: Job? = null

    /**
     * Pauses the player, then resolves the line at the paused position through
     * [SubtitleEngine.cueForCapture]. The player stays paused whatever the result.
     */
    fun capture(): CaptureResult {
        player.pause()
        val positionMs = player.positionMs.value
        val cue = engine.cueForCapture(positionMs) ?: return CaptureResult.NoLine
        mutableCaptured.value = CapturedLine(cue, capturedAtMs = positionMs)
        return CaptureResult.Captured
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
}
