package com.teachermovies.assistant

import com.teachermovies.assistant.subtitles.ParseResult
import com.teachermovies.assistant.subtitles.SidecarSubtitles
import com.teachermovies.assistant.subtitles.SubtitleParsers
import com.teachermovies.player.api.Player
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Outcome of asking a [HiddenSubtitleController] to enter hidden mode for a media file. */
sealed interface HiddenModeResult {
    /** The sidecar file was found and parsed; the engine has the track and subtitles are off. */
    data object Started : HiddenModeResult

    /** No sidecar `.srt`/`.ass` for the requested language exists next to the media file. */
    data object NoSubtitleFile : HiddenModeResult

    /** A sidecar file was found but could not be turned into cues; [reason] says why. */
    data class Unreadable(val reason: String) : HiddenModeResult
}

/**
 * Hidden EN subtitle mode (VISION §7, "Mostrar subtítulos: No"): the assistant reads the movie's
 * English subtitles in sync with playback while libVLC draws nothing on screen.
 *
 * It only talks to `:player` through [Player] and the value types of `com.teachermovies.player.api`
 * (AGENTS.md dependency direction); no libVLC type is referenced here.
 *
 * [engine] is expected to have been built over the same player's `positionMs` -- that is what makes
 * [SubtitleEngine.currentSubtitle] follow playback -- and [scope] is what keeps the on-screen
 * subtitles off: while [active], every non-null value of [Player.selectedSubtitleId] is turned back
 * off, so a default-track policy selecting a subtitle track cannot re-enable them behind the
 * assistant's back.
 */
class HiddenSubtitleController(
    private val player: Player,
    private val engine: SubtitleEngine,
    private val scope: CoroutineScope,
) {
    private val mutableActive = MutableStateFlow(false)

    /** True between a [start] that returned [HiddenModeResult.Started] and the next [stop]. */
    val active: StateFlow<Boolean> = mutableActive.asStateFlow()

    private var reassertJob: Job? = null

    /**
     * Enters hidden mode for [mediaFile] in [language]: resolves the sidecar subtitle file, parses
     * it, hands the track to [engine] and turns the player's own subtitles off.
     *
     * Any previous hidden-mode session is stopped first, so a failing call leaves [active] false
     * whatever the state was before it. Neither failure touches the player's subtitle selection:
     * the caller can fall back to on-screen subtitles without anything to undo.
     */
    fun start(
        mediaFile: File,
        language: String = "en",
    ): HiddenModeResult {
        stop()

        val subtitleFile =
            SidecarSubtitles.findFor(mediaFile, language)
                ?: return HiddenModeResult.NoSubtitleFile

        val track =
            when (val result = SubtitleParsers.parse(subtitleFile)) {
                is ParseResult.Parsed -> result.track
                is ParseResult.Malformed -> return HiddenModeResult.Unreadable(result.reason)
                is ParseResult.Unsupported ->
                    return HiddenModeResult.Unreadable(
                        "unsupported subtitle format .${result.extension}",
                    )
            }

        engine.load(track)
        player.selectSubtitle(null)
        mutableActive.value = true
        reassertJob =
            scope.launch {
                player.selectedSubtitleId.collect { id ->
                    if (id != null) {
                        player.selectSubtitle(null)
                    }
                }
            }

        return HiddenModeResult.Started
    }

    /**
     * Leaves hidden mode: clears the engine's track, stops re-asserting and sets [active] false.
     *
     * It deliberately does not select a subtitle track: whether the viewer wants on-screen
     * subtitles back is a decision for whoever turned hidden mode off, not for this controller.
     */
    fun stop() {
        reassertJob?.cancel()
        reassertJob = null
        engine.load(null)
        mutableActive.value = false
    }
}
