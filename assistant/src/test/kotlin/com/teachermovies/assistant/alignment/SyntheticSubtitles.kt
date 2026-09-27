package com.teachermovies.assistant.alignment

import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.assistant.subtitles.SubtitleTrack
import kotlin.random.Random

/** Synthetic subtitle tracks for the alignment tests: irregular timing, so no offset but the true one lines up. */
internal object SyntheticSubtitles {
    fun english(
        cues: Int = 400,
        seed: Int = 7,
        firstStartMs: Long = 20_000,
    ): SubtitleTrack {
        val random = Random(seed)
        var t = firstStartMs
        val list =
            (0 until cues).map { i ->
                val start = t + random.nextLong(500, 6_000)
                val end = start + random.nextLong(1_000, 4_000)
                t = end
                SubtitleCue(i, start, end, "en-$i")
            }
        return SubtitleTrack(list)
    }

    /** [english] retimed by `esMs = enMs * scale + offsetMs`, with up to [jitterMs] of per-edge noise. */
    fun spanishOf(
        english: SubtitleTrack,
        offsetMs: Long,
        scale: Double = 1.0,
        jitterMs: Long = 80,
        seed: Int = 11,
    ): SubtitleTrack {
        val random = Random(seed)

        fun noise() = if (jitterMs == 0L) 0L else random.nextLong(-jitterMs, jitterMs + 1)
        val list =
            english.cues.map { cue ->
                val start = (cue.startMs * scale).toLong() + offsetMs + noise()
                val end = (cue.endMs * scale).toLong() + offsetMs + noise()
                SubtitleCue(cue.index, start, maxOf(end, start + 1), "es-${cue.index}")
            }
        return SubtitleTrack(list.filter { it.endMs > 0 })
    }

    /**
     * An English track of pairs of short lines split by a pause (600 ms, 1 200 ms gap, 600 ms), and the
     * Spanish track with each pair merged into one cue shifted by [offsetMs]: every English cue alone
     * overlaps its Spanish cue by 25 %, below the 30 % threshold, and only the joined pair matches.
     */
    fun joinedPairs(
        pairs: Int = 200,
        offsetMs: Long = 0,
        seed: Int = 3,
    ): Pair<SubtitleTrack, SubtitleTrack> {
        val random = Random(seed)
        var t = 10_000L
        val en = mutableListOf<SubtitleCue>()
        val es = mutableListOf<SubtitleCue>()
        repeat(pairs) { p ->
            val a = t + random.nextLong(1_000, 4_000)
            val b = a + 1_800
            en += SubtitleCue(en.size, a, a + 600, "en-${en.size}")
            en += SubtitleCue(en.size, b, b + 600, "en-${en.size}")
            es += SubtitleCue(p, a + offsetMs, b + 600 + offsetMs, "es-$p")
            t = b + 600
        }
        return SubtitleTrack(en) to SubtitleTrack(es)
    }
}
