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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

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
    data class Started(
        val source: SubtitleSource,
    ) : HiddenModeResult

    /** Neither a sidecar `.srt`/`.ass` nor an embedded text track for the requested language exists. */
    data object NoSubtitleFile : HiddenModeResult

    /** A sidecar file or embedded track was found but could not be turned into cues; [reason] says why. */
    data class Unreadable(
        val reason: String,
    ) : HiddenModeResult
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
 * The viewer's own choice wins (#227): a subtitle track picked in the player's tracks panel goes
 * through [selectByViewer], which stops forcing subtitles off for the rest of this movie and applies
 * the choice. Hidden mode stays [active] and [engine] keeps its cues, so the assistant still reads
 * and captures lines; the next [start] (the next movie) forces subtitles off again.
 *
 * A choice the viewer made in an earlier session and persisted for the movie (#248) counts as a
 * viewer choice too: passed to [start] as `viewerSubtitleId`, that track is never turned off, so
 * the playback session can re-apply it on reopen while hidden mode keeps reading the cues; any other
 * track selected meanwhile (libVLC's own default while it starts the media) is reverted to that
 * track instead of to off.
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

    /** Bumped by every [stop]: a [start] still in flight when it changes gives up. */
    private val generation = MutableStateFlow(0L)

    /**
     * Enters hidden mode for [mediaFile] in [language]: resolves the cues, hands the track to
     * [engine] and turns the player's own subtitles off.
     *
     * The sidecar subtitle file wins ([SubtitleSource.SIDECAR]); only without one is the player's
     * embedded subtitle track for [language] ([EmbeddedSubtitleTracks.pick] over
     * [Player.subtitleTracks]) extracted into the cache and parsed ([SubtitleSource.EMBEDDED]).
     * [mediaFile] is expected to be the media currently open in [player]. libVLC publishes a media's
     * tracks only once it has parsed it, possibly after `PlaybackSession.open` returned: while
     * [Player.subtitleTracks] is still empty, this suspends (never blocks) until it publishes a
     * non-empty list, for at most [TRACKS_TIMEOUT_MS]; a list that is already known is used at once,
     * even when it has no track for [language]. A [stop] while [start] is still waiting or extracting
     * wins: that [start] returns [HiddenModeResult.NoSubtitleFile] at once and activates nothing.
     *
     * [viewerSubtitleId] is the subtitle track the viewer chose for this movie in an earlier session
     * (the persisted id, null = none): selecting it is left alone -- at start and while [active] --
     * so it survives a reopen (#248); any other track is reverted to it (or turned off when the
     * player does not list it). Whether it actually gets
     * selected is up to whoever re-applies the persisted choice (the playback session's track
     * policy), before or after this call.
     *
     * Any previous hidden-mode session is stopped first, so a failing call leaves [active] false
     * whatever the state was before it. No failure touches the player's subtitle selection: the
     * caller can fall back to on-screen subtitles without anything to undo.
     */
    suspend fun start(
        mediaFile: File,
        language: String = "en",
        viewerSubtitleId: String? = null,
    ): HiddenModeResult {
        stop()
        val session = generation.value

        val sidecar = SidecarSubtitles.findFor(mediaFile, language)
        val parsed: Parsing
        val source: SubtitleSource
        if (sidecar != null) {
            parsed = parse(sidecar)
            source = SubtitleSource.SIDECAR
        } else {
            val candidate =
                EmbeddedSubtitleTracks.pick(publishedSubtitleTracks(session), language)
                    ?: return HiddenModeResult.NoSubtitleFile
            parsed = embedded(mediaFile, candidate)
            source = SubtitleSource.EMBEDDED
        }
        val track =
            when (parsed) {
                is Parsing.Ok -> parsed.track
                is Parsing.Failed -> return parsed.result
            }
        if (generation.value != session) return HiddenModeResult.NoSubtitleFile

        engine.load(track)
        // The viewer's persisted choice may already be applied: selecting null here would both hide
        // it and let the session persist that null over it (#248).
        revertUnlessViewerChoice(player.selectedSubtitleId.value, viewerSubtitleId)
        mutableActive.value = true
        reassertJob =
            scope.launch {
                player.selectedSubtitleId.collect { id -> revertUnlessViewerChoice(id, viewerSubtitleId) }
            }

        return HiddenModeResult.Started(source)
    }

    /**
     * The viewer chose subtitle track [id] (null = `Desactivados`) in the tracks panel (#227). While
     * [active], it stops turning selections back off until the next [start], then selects [id] on
     * [player]; hidden mode itself (the cues in [engine]) is untouched. Outside hidden mode it only
     * selects [id]. Selections that do not come through here -- a default-track policy -- are still
     * reverted.
     */
    fun selectByViewer(id: String?) {
        reassertJob?.cancel()
        reassertJob = null
        player.selectSubtitle(id)
    }

    /**
     * Leaves hidden mode: clears the engine's track, stops re-asserting and sets [active] false.
     *
     * It deliberately does not select a subtitle track: whether the viewer wants on-screen
     * subtitles back is a decision for whoever turned hidden mode off, not for this controller.
     * Nothing under `cacheDir` is deleted.
     */
    fun stop() {
        generation.value += 1
        reassertJob?.cancel()
        reassertJob = null
        engine.load(null)
        mutableActive.value = false
    }

    /**
     * Undoes a subtitle selection [selected] that is neither off nor [viewerSubtitleId]: back to the
     * viewer's track when there is one and the player lists it, otherwise off.
     *
     * Going back to the viewer's track rather than off matters on reopen (#248): while libVLC starts
     * the media it briefly reports its own default subtitle -- after the session has already asked
     * for the stored one -- and turning that off would also cancel the stored track, since libVLC
     * applies the last request. A viewer track the player refuses falls back to off, so hidden mode
     * never leaves a track on screen that nobody chose.
     */
    private fun revertUnlessViewerChoice(
        selected: String?,
        viewerSubtitleId: String?,
    ) {
        if (selected == null || selected == viewerSubtitleId) return
        val restorable = viewerSubtitleId != null && player.subtitleTracks.value.any { it.id == viewerSubtitleId }
        if (restorable) {
            player.selectSubtitle(viewerSubtitleId)
            if (player.selectedSubtitleId.value == viewerSubtitleId) return
        }
        player.selectSubtitle(null)
    }

    /**
     * The player's subtitle tracks: the current list when it is not empty, otherwise the first
     * non-empty list published within [TRACKS_TIMEOUT_MS]; an empty list when none arrives or when
     * [stop] ends [session] first.
     */
    private suspend fun publishedSubtitleTracks(session: Long): List<Track> {
        val known = player.subtitleTracks.value
        if (known.isNotEmpty()) return known
        val published =
            withTimeoutOrNull(TRACKS_TIMEOUT_MS) {
                combine(player.subtitleTracks, generation) { tracks, current -> tracks.takeIf { current == session } }
                    .first { it == null || it.isNotEmpty() }
            }
        return published ?: emptyList()
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
        data class Ok(
            val track: SubtitleTrack,
        ) : Parsing

        data class Failed(
            val result: HiddenModeResult.Unreadable,
        ) : Parsing
    }

    companion object {
        /** How long [start] waits for [Player.subtitleTracks] to publish tracks after an empty list. */
        const val TRACKS_TIMEOUT_MS: Long = 3_000L

        private const val SUBTITLE_CACHE_DIR = "subtitles"

        private val UNSAFE_ID_CHARS = Regex("[^A-Za-z0-9_-]")

        private fun sanitise(trackId: String): String = trackId.replace(UNSAFE_ID_CHARS, "_").ifEmpty { "_" }

        private fun extensionOf(format: SubtitleFormat): String =
            when (format) {
                SubtitleFormat.SRT -> "srt"
                SubtitleFormat.ASS -> "ass"
            }
    }
}
