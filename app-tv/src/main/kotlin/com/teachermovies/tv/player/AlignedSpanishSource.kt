package com.teachermovies.tv.player

import com.teachermovies.assistant.EmbeddedSubtitle
import com.teachermovies.assistant.alignment.SpanishLineLookup
import com.teachermovies.assistant.alignment.SpanishLineResult
import com.teachermovies.assistant.alignment.SpanishSubtitleCandidate
import com.teachermovies.assistant.alignment.SpanishSubtitleSource
import com.teachermovies.assistant.subtitles.ParseResult
import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.assistant.subtitles.SubtitleParsers
import com.teachermovies.assistant.subtitles.SubtitleTrack
import com.teachermovies.core.log.AppLog
import com.teachermovies.core.model.SubtitleFetchState
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.SubtitleFetchRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Spanish subtitle line aligned to a captured English cue (#288, ADR-0005 §6): [text] as the
 * subtitle file has it, [latino] when that file is the Latin-American one accepted as a last resort
 * (ADR-0005 §5), so the panel can say so.
 */
data class AlignedSpanishLine(
    val text: String,
    val latino: Boolean = false,
)

/**
 * LEFT's first answer (#288): the aligned Spanish line for [cue] of the movie [torrentId] playing
 * from [mediaFile], or null when no Spanish subtitle aligns well enough to be trusted -- then the
 * caller asks the bridge instead. Never throws (cancellation aside).
 */
fun interface AlignedSpanishSource {
    suspend fun lineFor(
        torrentId: TorrentId,
        mediaFile: File,
        cue: SubtitleCue,
    ): AlignedSpanishLine?

    companion object {
        /** No Spanish subtitles at all: every LEFT goes to the bridge. */
        val NONE = AlignedSpanishSource { _, _, _ -> null }
    }
}

/** A Spanish subtitle track found for a movie, and whether it is the Latin-American variant. */
data class FoundSpanishSubtitle(
    val candidate: SpanishSubtitleCandidate,
    val latino: Boolean = false,
)

/**
 * [AlignedSpanishSource] over #283's [SpanishLineLookup]: [english] is the track hidden mode is
 * reading (null outside hidden mode, which answers null), [find] the movie's Spanish tracks. The
 * tracks are looked up again on every press, so a subtitle the bridge downloads mid-movie counts
 * from the next LEFT on; the alignments themselves are cached by the lookup.
 *
 * A lookup that throws (the alignment cache is Room I/O) is logged and answers null, so LEFT still
 * falls back to the bridge instead of failing.
 */
class LookupAlignedSpanishSource(
    private val lookup: SpanishLineLookup,
    private val english: () -> SubtitleTrack?,
    private val find: suspend (TorrentId, File) -> List<FoundSpanishSubtitle>,
) : AlignedSpanishSource {
    override suspend fun lineFor(
        torrentId: TorrentId,
        mediaFile: File,
        cue: SubtitleCue,
    ): AlignedSpanishLine? {
        val englishTrack = english() ?: return null
        return try {
            val found = find(torrentId, mediaFile)
            if (found.isEmpty()) return null
            when (val result = lookup.lineFor(torrentId, englishTrack, cue, found.map { it.candidate })) {
                is SpanishLineResult.Found -> {
                    val latino = found.any { it.latino && it.candidate.path == result.line.subtitlePath }
                    AlignedSpanishLine(result.line.text, latino)
                }

                SpanishLineResult.NoGoodAlignment, SpanishLineResult.NoMatchingLine -> {
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(LOG_MODULE, "aligned Spanish lookup failed; falling back to the bridge", e)
            null
        }
    }

    private companion object {
        const val LOG_MODULE = "app-tv"
    }
}

/**
 * Finds a movie's Spanish subtitle tracks for [LookupAlignedSpanishSource], in ADR-0005 §6's order:
 * a sidecar `*.es.srt`/`*.es.ass` (or `.spa.`) next to the movie or in its `Subs` directory, the
 * container's embedded Spanish text track ([embedded] asked for each of [EMBEDDED_LANGUAGES] in turn,
 * extracted once into the cache), and the file
 * the laptop bridge downloaded ([fetches]' `es` row once `Downloaded`, [FoundSpanishSubtitle.latino]
 * when its variant is `"latino"`). A sidecar that is the downloaded file itself counts once, as the
 * download, so its variant is not lost. A file that is missing or does not parse is left out.
 * Sidecar and downloaded files are read on [dispatcher]; [embedded] runs on the caller's thread.
 */
class SpanishSubtitleFinder(
    private val fetches: SubtitleFetchRepository,
    private val embedded: suspend (File, String) -> EmbeddedSubtitle?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun find(
        torrentId: TorrentId,
        mediaFile: File,
    ): List<FoundSpanishSubtitle> {
        val download =
            fetches
                .get(torrentId, SPANISH)
                ?.takeIf { it.state == SubtitleFetchState.Downloaded }
        val downloadedPath = download?.localPath?.let { File(it).absolutePath }
        val sidecars =
            withContext(dispatcher) {
                sidecarsOf(mediaFile)
                    .filter { it.absolutePath != downloadedPath }
                    .mapNotNull { file -> parsed(file)?.let { candidate(SpanishSubtitleSource.SIDECAR, file, it) } }
            }
        // On the caller's thread: the extraction talks to the player, which lives on the main thread.
        val embeddedTrack =
            EMBEDDED_LANGUAGES
                .firstNotNullOfOrNull { embedded(mediaFile, it) }
                ?.let { candidate(SpanishSubtitleSource.EMBEDDED, it.file, it.track) }
        val downloaded =
            downloadedPath?.let { path ->
                val file = File(path)
                withContext(dispatcher) { parsed(file) }?.let {
                    candidate(SpanishSubtitleSource.DOWNLOADED, file, it, latino = download.variantLabel == LATINO)
                }
            }
        return sidecars + listOfNotNull(embeddedTrack, downloaded)
    }

    private fun candidate(
        source: SpanishSubtitleSource,
        file: File,
        track: SubtitleTrack,
        latino: Boolean = false,
    ) = FoundSpanishSubtitle(SpanishSubtitleCandidate(source, file.absolutePath, track), latino)

    /** Spanish sidecars of [mediaFile]: its own directory first, then `Subs`, each sorted by name. */
    private fun sidecarsOf(mediaFile: File): List<File> {
        val parent = mediaFile.absoluteFile.parentFile ?: return emptyList()
        val subs = parent.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(SUBS, ignoreCase = true) }
        return listOfNotNull(parent, subs).flatMap { dir ->
            (dir.listFiles() ?: emptyArray())
                .filter { it.isFile && isSpanishSubtitleName(it.name) }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
        }
    }

    private fun parsed(file: File): SubtitleTrack? =
        if (!file.isFile) {
            null
        } else {
            when (val result = SubtitleParsers.parse(file)) {
                is ParseResult.Parsed -> {
                    result.track
                }

                is ParseResult.Malformed, is ParseResult.Unsupported -> {
                    AppLog.w(LOG_MODULE, "Spanish subtitle ${file.name} is not readable; skipped")
                    null
                }
            }
        }

    companion object {
        const val SPANISH = "es"
        const val LATINO = "latino"

        /** Codes an embedded Spanish track may declare: ISO 639-1 `es`, then ISO 639-2 `spa`. */
        val EMBEDDED_LANGUAGES = listOf("es", "spa")
        private const val SUBS = "Subs"
        private const val LOG_MODULE = "app-tv"
        private val SPANISH_NAME = Regex(""".*\.(es|spa)\.(srt|ass)""", RegexOption.IGNORE_CASE)

        /** Whether [name] is a Spanish subtitle sidecar: `<anything>.es.srt`, `.spa.ass`, ... */
        fun isSpanishSubtitleName(name: String): Boolean = SPANISH_NAME.matches(name)
    }
}
