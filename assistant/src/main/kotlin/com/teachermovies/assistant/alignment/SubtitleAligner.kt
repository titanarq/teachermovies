package com.teachermovies.assistant.alignment

import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.assistant.subtitles.SubtitleTrack
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * How a Spanish track lines up with the English one: `esMs = enMs * [frameRateScale] + [offsetMs]`,
 * graded by [qualityScore] (0..1, the share of English cues that found a Spanish match). Same
 * convention as `com.teachermovies.core.model.SubtitleAlignment`, which is how it is cached.
 */
data class AlignmentFit(
    val offsetMs: Long,
    val frameRateScale: Double,
    val qualityScore: Double,
) {
    /** Whether the alignment is good enough to show its Spanish lines ([SubtitleAligner.GOOD_QUALITY]). */
    val isGood: Boolean get() = qualityScore >= SubtitleAligner.GOOD_QUALITY
}

/**
 * Finds the global offset and frame-rate scale between an English and a Spanish subtitle track
 * (ADR-0005 §6), from cue timing alone -- the texts are in different languages.
 *
 * For each scale in [scales] (by default `1`, `25/23.976` and `23.976/25`: the same film at PAL speed
 * or not), every pair of cue starts closer than [maxOffsetMs] votes for its offset in a histogram of
 * [stepMs] bins. The strongest peaks (and the bins next to them) are then scored: an English cue
 * matches when it overlaps a Spanish cue by at least [minOverlap] (intersection over union), each
 * Spanish cue matching at most once; two adjacent English cues that match nothing alone may be joined
 * against a single Spanish cue ([maxJoinedCues], at most 2), as when a translator merged two short
 * lines. The candidate with the largest total overlap wins (ties: the earlier scale in [scales], then
 * the smaller offset). Its quality is the share of cues it matched -- Spanish cues matched over the
 * smaller of the two tracks' cue counts, so a translation that dropped lines is not penalised for
 * them -- corrected for chance: with
 * `m` that share and `c` the median share at eight decoy offsets 5-23 s away from it (what unrelated
 * timelines of the same density reach), `quality = (m - c) / (1 - c)`, clamped to 0..1 -- 1 for a
 * perfect match, about 0 for an unrelated track. An empty track, or no match at all, scores 0.
 */
class SubtitleAligner(
    private val maxOffsetMs: Long = 15_000,
    private val stepMs: Long = 100,
    private val scales: List<Double> = DEFAULT_SCALES,
    private val minOverlap: Double = MIN_OVERLAP,
    private val maxJoinedCues: Int = 2,
    private val peaksPerScale: Int = 5,
) {
    init {
        require(maxOffsetMs >= 0 && stepMs > 0) { "invalid offset window" }
        require(scales.isNotEmpty()) { "no scales" }
        require(maxJoinedCues in 1..2) { "maxJoinedCues must be 1 or 2" }
    }

    fun align(
        english: SubtitleTrack,
        spanish: SubtitleTrack,
    ): AlignmentFit {
        val en = english.cues.sortedBy { it.startMs }
        if (en.isEmpty() || spanish.cues.isEmpty()) return AlignmentFit(0, 1.0, 0.0)
        val matcher = CueMatcher(spanish.cues, minOverlap)
        val esStarts =
            spanish.cues
                .map { it.startMs }
                .sorted()
                .toLongArray()

        // A share of what could match at all: a Spanish file missing lines is not a worse alignment.
        val matchable = minOf(en.size, spanish.cues.size).toDouble()

        fun share(matched: Int) = matched / matchable
        var best = AlignmentFit(0, scales.first(), 0.0)
        var bestMatched = 0.0
        var bestMass = 0.0
        for (scale in scales) {
            for (bin in candidateBins(en, esStarts, scale)) {
                val (matched, mass) = score(en, matcher, scale, bin * stepMs)
                if (mass > bestMass + EPSILON) {
                    bestMass = mass
                    bestMatched = share(matched)
                    best = AlignmentFit(bin * stepMs, scale, 0.0)
                }
            }
        }
        if (bestMass == 0.0) return best
        val chance =
            DECOY_OFFSETS_MS
                .map { share(score(en, matcher, best.frameRateScale, best.offsetMs + it).first) }
                .sorted()
                .let { (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
        val quality = if (chance >= 1.0) 0.0 else ((bestMatched - chance) / (1.0 - chance)).coerceIn(0.0, 1.0)
        return best.copy(qualityScore = quality)
    }

    /** Histogram bins worth scoring for [scale], closest to zero first. */
    private fun candidateBins(
        en: List<SubtitleCue>,
        esStarts: LongArray,
        scale: Double,
    ): List<Long> {
        val maxBin = maxOffsetMs / stepMs
        val votes = IntArray((2 * maxBin + 1).toInt())
        for (cue in en) {
            val mapped = cue.startMs * scale
            var i = lowerBound(esStarts, (mapped - maxOffsetMs).toLong())
            while (i < esStarts.size && esStarts[i] <= mapped + maxOffsetMs) {
                val bin = ((esStarts[i] - mapped) / stepMs).roundToLong()
                if (abs(bin) <= maxBin) votes[(bin + maxBin).toInt()]++
                i++
            }
        }
        val smoothed = IntArray(votes.size) { k -> (k - 1..k + 1).sumOf { votes.getOrElse(it) { 0 } } }
        val peaks =
            smoothed.indices
                .filter { smoothed[it] > 0 }
                .sortedWith(compareByDescending<Int> { smoothed[it] }.thenBy { abs(it - maxBin) })
                .take(peaksPerScale)
        return peaks
            .flatMap { listOf(it - 1, it, it + 1) }
            .filter { it in votes.indices }
            .map { it - maxBin }
            .distinct()
            .sortedBy { abs(it) }
    }

    /**
     * How many Spanish cues are matched under ([scale], [offsetMs]), and the sum of the matched English
     * cues' overlaps. Each Spanish cue matches at most one English cue (or one joined pair), in English
     * order.
     */
    private fun score(
        en: List<SubtitleCue>,
        matcher: CueMatcher,
        scale: Double,
        offsetMs: Long,
    ): Pair<Int, Double> {
        val taken = BooleanArray(matcher.size)
        var matched = 0
        var mass = 0.0
        var i = 0
        while (i < en.size) {
            val alone = matcher.bestMatch(en[i], en[i], scale, offsetMs, taken)
            val joined =
                if (alone == null && maxJoinedCues >= 2 && i + 1 < en.size &&
                    matcher.bestMatch(en[i + 1], en[i + 1], scale, offsetMs, taken) == null
                ) {
                    matcher.bestMatch(en[i], en[i + 1], scale, offsetMs, taken)
                } else {
                    null
                }
            val match = alone ?: joined
            if (match != null) {
                val cues = if (alone != null) 1 else 2
                taken[match.position] = true
                matched++
                mass += cues * match.overlap
            }
            i += if (joined != null) 2 else 1
        }
        return matched to mass
    }

    private fun lowerBound(
        values: LongArray,
        key: Long,
    ): Int {
        var lo = 0
        var hi = values.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (values[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** An alignment scoring at least this is trusted to show its Spanish lines. */
        const val GOOD_QUALITY = 0.6

        /** The minimum intersection-over-union for an English cue (or two joined) to match a Spanish cue. */
        const val MIN_OVERLAP = 0.3

        val DEFAULT_SCALES: List<Double> = listOf(1.0, 25.0 / 23.976, 23.976 / 25.0)

        /**
         * Shifts away from the winning offset where two unrelated timelines are compared, to measure how
         * many cues match by chance (dense dialogue overlaps something at almost any offset).
         */
        private val DECOY_OFFSETS_MS = listOf(-23_300L, -17_900L, -11_700L, -5_300L, 5_300L, 11_700L, 17_900L, 23_300L)

        private const val EPSILON = 1e-9
    }
}
