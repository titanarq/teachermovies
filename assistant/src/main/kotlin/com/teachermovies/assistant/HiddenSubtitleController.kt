package com.teachermovies.assistant

import com.teachermovies.assistant.subtitles.EmbeddedSubtitleTracks
import com.teachermovies.assistant.subtitles.ParseResult
import com.teachermovies.assistant.subtitles.SidecarSubtitles
import com.teachermovies.assistant.subtitles.SubtitleParsers
import com.teachermovies.assistant.subtitles.SubtitleTrack
import com.teachermovies.player.api.Player
import com.teachermovies.player.api.SubtitleExtraction
import com.teachermovies.player.api.SubtitleFormat
import com.teachermovies.player.api.Track
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the cues of a hidden-mode session came from. */
enum class SubtitleSource {
    /** A standalone `.srt`/`.ass` file next to the movie ([SidecarSubtitles]). */
    SIDECAR,

    /** A text subtitle track embedded in the movie's container, extracted through [Player]. */
    EMBEDDED,
}

/** Outcome of asking a [HiddenSubtitleController] to enter hidden mode for a media file. */
sealed interface HiddenModeResult {
    /** Cues were found and parsed from [source]; the engine has the track and subtitles are off. */
    data class Started(val source: SubtitleSource) : HiddenModeResult

    /** Neither a sidecar `.srt`/`.ass` nor an embedded text track for the requested language exists. */
    data object NoSubtitleFile : HiddenModeResult

    /** A sidecar file or embedded track was found but could not be turned into cues; [reason] says why. */
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
 *
 * Embedded tracks are extracted under `<cacheDir>/subtitles` and reused by later sessions for the
 * same movie; nothing here deletes them.
 */
class HiddenSubtitleController(
    private val player: Player,
    private val engine: SubtitleEngine,
    private val scope: CoroutineScope,
    private val cacheDir: File,
) {
    private val mutableActive = MutableStateFlow(false)

    /** True between a [start] that returned [HiddenModeResult.Started] and the next [stop]. */
    val active: StateFlow<Boolean> = mutableActive.asStateFlow()

    private var reassertJob: Job? = null

    /**
     * Enters hidden mode for [mediaFile] in [language]: resolves the cues, hands the track to
     * [engine] and turns the player's own subtitles off.
     *
     * The sidecar subtitle file wins ([SubtitleSource.SIDECAR]); only without one is the player's
     * embedded subtitle track for [language] ([EmbeddedSubtitleTracks.pick] over
     * [Player.subtitleTracks]) extracted into the cache and parsed ([SubtitleSource.EMBEDDED]).
     * [mediaFile] is expected to be the media currently open in [player].
     *
     * Any previous hidden-mode session is stopped first, so a failing call leaves [active] false
     * whatever the state was before it. No failure touches the player's subtitle selection: the
     * caller can fall back to on-screen subtitles without anything to undo.
     */
    suspend fun start(
        mediaFile: File,
        language: String = "en",
    ): HiddenModeResult {
        stop()

        val sidecar = SidecarSubtitles.findFor(mediaFile, language)
        val parsed: Parsing
        val source: SubtitleSource
        if (sidecar != null) {
            parsed = parse(sidecar)
            source = SubtitleSource.SIDECAR
        } else {
            val candidate =
                EmbeddedSubtitleTracks.pick(player.subtitleTracks.value, language)
                    ?: return HiddenModeResult.NoSubtitleFile
            parsed = embedded(mediaFile, candidate)
            source = SubtitleSource.EMBEDDED
        }
        val track =
            when (parsed) {
                is Parsing.Ok -> parsed.track
                is Parsing.Failed -> return parsed.result
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

        return HiddenModeResult.Started(source)
    }

    /**
     * Leaves hidden mode: clears the engine's track, stops re-asserting and sets [active] false.
     *
     * It deliberately does not select a subtitle track: whether the viewer wants on-screen
     * subtitles back is a decision for whoever turned hidden mode off, not for this controller.
     * Nothing under `cacheDir` is deleted.
     */
    fun stop() {
        reassertJob?.cancel()
        reassertJob = null
        engine.load(null)
        mutableActive.value = false
    }

    /** Cues of the embedded [candidate], from its cache file when a previous session wrote one. */
    private suspend fun embedded(
        mediaFile: File,
        candidate: Track,
    ): Parsing {
        val dir = File(cacheDir, SUBTITLE_CACHE_DIR)
        val baseName = "${mediaFile.nameWithoutExtension}.${sanitise(candidate.id)}"
        val cached =
            SubtitleFormat.entries
                .map { File(dir, "$baseName.${extensionOf(it)}") }
                .firstOrNull { it.isFile && it.length() > 0 }
        if (cached != null) return parse(cached)

        if (!dir.isDirectory && !dir.mkdirs()) {
            return unreadable("cannot create the subtitle cache directory")
        }
        // The format is only known once extracted and the parser dispatches on the extension: write
        // to a scratch name, then move the result under its final `.srt`/`.ass` name.
        val scratch = File(dir, "$baseName.part")
        val extracted =
            when (val result = player.extractTextSubtitle(candidate.id, scratch)) {
                is SubtitleExtraction.Extracted -> result
                is SubtitleExtraction.Failed -> return unreadable("embedded track extraction failed: ${result.reason}")
                SubtitleExtraction.NotTextBased -> return unreadable("embedded track is image-based (not text)")
                SubtitleExtraction.TrackNotFound -> return unreadable("embedded track not found in the open media")
            }
        val target = File(dir, "$baseName.${extensionOf(extracted.format)}")
        if (extracted.file != target) {
            target.delete()
            if (!extracted.file.renameTo(target)) {
                extracted.file.delete()
                return unreadable("cannot store the extracted subtitle track")
            }
        }
        return parse(target)
    }

    private fun parse(file: File): Parsing =
        when (val result = SubtitleParsers.parse(file)) {
            is ParseResult.Parsed -> Parsing.Ok(result.track)
            is ParseResult.Malformed -> unreadable(result.reason)
            is ParseResult.Unsupported -> unreadable("unsupported subtitle format .${result.extension}")
        }

    private fun unreadable(reason: String): Parsing = Parsing.Failed(HiddenModeResult.Unreadable(reason))

    private sealed interface Parsing {
        data class Ok(val track: SubtitleTrack) : Parsing

        data class Failed(val result: HiddenModeResult.Unreadable) : Parsing
    }

    private companion object {
        const val SUBTITLE_CACHE_DIR = "subtitles"

        val UNSAFE_ID_CHARS = Regex("[^A-Za-z0-9_-]")

        fun sanitise(trackId: String): String = trackId.replace(UNSAFE_ID_CHARS, "_").ifEmpty { "_" }

        fun extensionOf(format: SubtitleFormat): String =
            when (format) {
                SubtitleFormat.SRT -> "srt"
                SubtitleFormat.ASS -> "ass"
            }
    }
}
