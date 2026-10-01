package com.teachermovies.assistant.alignment

import com.teachermovies.assistant.SpanishTextSource
import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.assistant.subtitles.SubtitleTrack
import com.teachermovies.core.model.SubtitleAlignment
import com.teachermovies.core.model.TorrentId
import kotlinx.coroutines.CancellationException

/**
 * The Spanish subtitle text of any playback position, from an aligned Spanish track (#344).
 *
 * A playback position is on the English timeline; [textAt] maps it to the Spanish one with
 * `esMs = enMs * frameRateScale + offsetMs` (the [SubtitleAlignment] convention) and returns the text of
 * the Spanish cue containing that time (`startMs <= esMs < endMs`), or `null` in a gap. The lookup is a
 * binary search over the cues sorted by start.
 */
class SpanishCueTimeline internal constructor(
    spanish: SubtitleTrack,
    private val alignment: SubtitleAlignment,
) : SpanishTextSource {
    private val cues: List<SubtitleCue> = spanish.cues.sortedBy { it.startMs }

    override fun textAt(positionMs: Long): String? {
        val esMs = CueMatcher.map(positionMs, alignment.frameRateScale, alignment.offsetMs)
        var lo = 0
        var hi = cues.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= esMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return cues.getOrNull(found)?.takeIf { esMs < it.endMs }?.text
    }

    companion object {
        /**
         * The timeline of the first candidate (sidecar, embedded, downloaded) whose alignment reaches
         * [SubtitleAligner.GOOD_QUALITY], or `null` when none does. Never throws (cancellation aside): a
         * failing alignment lookup also gives `null`.
         */
        suspend fun create(
            lookup: SpanishLineLookup,
            torrentId: TorrentId,
            english: SubtitleTrack,
            candidates: List<SpanishSubtitleCandidate>,
        ): SpanishCueTimeline? =
            try {
                candidates.sortedBy { it.source.ordinal }.firstNotNullOfOrNull { candidate ->
                    val alignment = lookup.alignmentFor(torrentId, english, candidate)
                    if (alignment.qualityScore < SubtitleAligner.GOOD_QUALITY) {
                        null
                    } else {
                        SpanishCueTimeline(candidate.track, alignment)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                null
            }
    }
}
