package com.teachermovies.assistant.alignment

import com.teachermovies.assistant.subtitles.SubtitleCue
import kotlin.math.max
import kotlin.math.min

/**
 * Time-overlap matching of English cues, mapped onto the Spanish timeline by
 * `esMs = enMs * scale + offsetMs`, against the cues of one Spanish track (ADR-0005 §6). The aligner
 * scores a candidate alignment with it and [SpanishLineLookup] picks the line with it, so both agree
 * on what "matching" means.
 *
 * Overlap is intersection over union of the two time ranges, so a long Spanish cue can not "match"
 * every short English cue it happens to cover: that is what makes joining two adjacent English cues
 * against one Spanish cue meaningful.
 */
internal class CueMatcher(
    spanish: List<SubtitleCue>,
    private val minOverlap: Double,
) {
    private val cues = spanish.sortedBy { it.startMs }
    private val starts = LongArray(cues.size) { cues[it].startMs }
    private val maxDurationMs = cues.maxOfOrNull { it.endMs - it.startMs } ?: 0L

    /** How many Spanish cues there are; [Match.position] is in `0 until size`. */
    val size: Int get() = cues.size

    /**
     * The Spanish cue that overlaps `[startMs, endMs)` the most, when that overlap is at least
     * [minOverlap], skipping the cues [taken] marks (by [Match.position]).
     */
    fun bestMatch(
        startMs: Long,
        endMs: Long,
        taken: BooleanArray? = null,
    ): Match? {
        if (endMs <= startMs || cues.isEmpty()) return null
        var best: Match? = null
        var i = firstStartAtLeast(startMs - maxDurationMs)
        while (i < cues.size && starts[i] < endMs) {
            val cue = cues[i]
            val overlap = overlap(startMs, endMs, cue.startMs, cue.endMs)
            if (overlap >= minOverlap && taken?.get(i) != true && (best == null || overlap > best.overlap)) {
                best = Match(cue, i, overlap)
            }
            i++
        }
        return best
    }

    /** The Spanish cue matching the English cues from [first] to [last] joined into one range under ([scale], [offsetMs]). */
    fun bestMatch(
        first: SubtitleCue,
        last: SubtitleCue,
        scale: Double,
        offsetMs: Long,
        taken: BooleanArray? = null,
    ): Match? = bestMatch(map(first.startMs, scale, offsetMs), map(last.endMs, scale, offsetMs), taken)

    private fun firstStartAtLeast(ms: Long): Int {
        var lo = 0
        var hi = starts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] < ms) lo = mid + 1 else hi = mid
        }
        return lo
    }

    data class Match(
        val cue: SubtitleCue,
        val position: Int,
        val overlap: Double,
    )

    companion object {
        fun map(
            enMs: Long,
            scale: Double,
            offsetMs: Long,
        ): Long = (enMs * scale).toLong() + offsetMs

        fun overlap(
            aStart: Long,
            aEnd: Long,
            bStart: Long,
            bEnd: Long,
        ): Double {
            val intersection = min(aEnd, bEnd) - max(aStart, bStart)
            if (intersection <= 0) return 0.0
            val union = max(aEnd, bEnd) - min(aStart, bStart)
            return intersection.toDouble() / union
        }
    }
}
