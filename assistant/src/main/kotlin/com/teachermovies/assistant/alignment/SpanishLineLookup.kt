package com.teachermovies.assistant.alignment

import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.assistant.subtitles.SubtitleTrack
import com.teachermovies.core.model.SubtitleAlignment
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.SubtitleAlignmentRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a Spanish subtitle track comes from, in preference order (ADR-0005 §6). */
enum class SpanishSubtitleSource {
    /** A standalone Spanish file shipped next to the movie. */
    SIDECAR,

    /** A Spanish text track inside the movie's own container. */
    EMBEDDED,

    /** A file the laptop bridge downloaded from OpenSubtitles. */
    DOWNLOADED,
}

/** One Spanish track that could answer the panel; [path] identifies it in the alignment cache. */
data class SpanishSubtitleCandidate(
    val source: SpanishSubtitleSource,
    val path: String,
    val track: SubtitleTrack,
)

/** The Spanish line aligned to a captured English cue. */
data class SpanishLine(
    val text: String,
    val cue: SubtitleCue,
    val source: SpanishSubtitleSource,
    val subtitlePath: String,
    val qualityScore: Double,
)

sealed interface SpanishLineResult {
    data class Found(
        val line: SpanishLine,
    ) : SpanishLineResult

    /** No candidate aligned well enough to be trusted (or there were none). */
    data object NoGoodAlignment : SpanishLineResult

    /** At least one candidate aligned well, but none has a Spanish cue overlapping this English one. */
    data object NoMatchingLine : SpanishLineResult
}

/**
 * The Spanish line for a captured English cue (ADR-0005 §6), when an alignment is good enough to trust.
 *
 * Candidates are tried sidecar, then embedded, then downloaded (list order within one source). Each
 * one's alignment is read from [repository] by (torrent, path) or, the first time, computed by
 * [aligner] on [dispatcher] and saved with `computedAtEpochMs = nowMs()`. A candidate scoring under
 * [SubtitleAligner.GOOD_QUALITY] is skipped; in the first good one, the cue is mapped onto the Spanish
 * timeline and the Spanish cue overlapping it most (at least [SubtitleAligner.MIN_OVERLAP], intersection
 * over union) is the answer. When the cue alone matches nothing, it is joined with the previous or the
 * next English cue (whichever overlaps more) when that neighbour matches nothing alone either, the
 * same 2-cue join the aligner scores with. A good
 * candidate without a match falls through to the next good one. What to show when there is no answer
 * (the bridge translation) is the caller's (#288).
 */
class SpanishLineLookup(
    private val repository: SubtitleAlignmentRepository,
    private val nowMs: () -> Long,
    private val aligner: SubtitleAligner = SubtitleAligner(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun lineFor(
        torrentId: TorrentId,
        english: SubtitleTrack,
        cue: SubtitleCue,
        candidates: List<SpanishSubtitleCandidate>,
    ): SpanishLineResult {
        var anyGood = false
        for (candidate in candidates.sortedBy { it.source.ordinal }) {
            val alignment = alignmentFor(torrentId, english, candidate)
            if (alignment.qualityScore < SubtitleAligner.GOOD_QUALITY) continue
            anyGood = true
            val match = match(english, cue, candidate.track, alignment) ?: continue
            return SpanishLineResult.Found(
                SpanishLine(match.text, match, candidate.source, candidate.path, alignment.qualityScore),
            )
        }
        return if (anyGood) SpanishLineResult.NoMatchingLine else SpanishLineResult.NoGoodAlignment
    }

    /** The cached alignment of [candidate], computing and caching it on a miss. */
    suspend fun alignmentFor(
        torrentId: TorrentId,
        english: SubtitleTrack,
        candidate: SpanishSubtitleCandidate,
    ): SubtitleAlignment {
        repository.get(torrentId, candidate.path)?.let { return it }
        val fit = withContext(dispatcher) { aligner.align(english, candidate.track) }
        val alignment =
            SubtitleAlignment(
                torrentId = torrentId,
                subtitlePath = candidate.path,
                offsetMs = fit.offsetMs,
                frameRateScale = fit.frameRateScale,
                qualityScore = fit.qualityScore,
                computedAtEpochMs = nowMs(),
            )
        repository.save(alignment)
        return alignment
    }

    private fun match(
        english: SubtitleTrack,
        cue: SubtitleCue,
        spanish: SubtitleTrack,
        alignment: SubtitleAlignment,
    ): SubtitleCue? {
        val matcher = CueMatcher(spanish.cues, SubtitleAligner.MIN_OVERLAP)
        val scale = alignment.frameRateScale
        val offset = alignment.offsetMs
        matcher.bestMatch(cue, cue, scale, offset)?.let { return it.cue }
        val en = english.cues.sortedBy { it.startMs }
        val at = en.indexOfFirst { it.startMs == cue.startMs && it.endMs == cue.endMs && it.text == cue.text }
        if (at < 0) return null

        // Only a neighbour that matches nothing alone may be joined, as in the aligner's score.
        fun orphan(neighbour: SubtitleCue?) = neighbour?.takeIf { matcher.bestMatch(it, it, scale, offset) == null }
        val withPrevious = orphan(en.getOrNull(at - 1))?.let { matcher.bestMatch(it, cue, scale, offset) }
        val withNext = orphan(en.getOrNull(at + 1))?.let { matcher.bestMatch(cue, it, scale, offset) }
        return listOfNotNull(withPrevious, withNext).maxByOrNull { it.overlap }?.cue
    }
}
