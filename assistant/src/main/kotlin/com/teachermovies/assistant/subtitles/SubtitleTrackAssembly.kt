package com.teachermovies.assistant.subtitles

/**
 * A cue as read off disk, before the ordering/renumbering rules that every [SubtitleParser] shares
 * are applied by [buildTrack].
 */
internal data class RawCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * Applies the ordering rule every [SubtitleParser] shares: cues sorted by [RawCue.startMs],
 * renumbered `0..n-1` in that order, with `endMs >= startMs` (clamped rather than dropped, so a
 * single bad timestamp does not silently discard real cue text).
 */
internal fun buildTrack(rawCues: List<RawCue>): SubtitleTrack {
    val cues = rawCues
        .sortedBy { it.startMs }
        .mapIndexed { index, raw ->
            SubtitleCue(
                index = index,
                startMs = raw.startMs,
                endMs = maxOf(raw.startMs, raw.endMs),
                text = raw.text,
            )
        }
    return SubtitleTrack(cues)
}
