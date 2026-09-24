package com.teachermovies.assistant.subtitles

/**
 * Looks up the [SubtitleCue] spoken at a given playback position, by binary search over the
 * cues sorted by [SubtitleCue.startMs] -- never a linear scan, however long the track is.
 *
 * A cue covers the half-open range `[startMs, endMs)`: a position exactly at [SubtitleCue.endMs]
 * belongs to whatever comes next, not to that cue.
 */
class SubtitleIndex(track: SubtitleTrack) {
    private val cues: List<SubtitleCue> = track.cues.sortedBy { it.startMs }

    /**
     * The cue spoken at [positionMs], or `null` before the first cue, after the last one, or in a
     * gap between cues.
     *
     * When cues overlap (common in ASS, where two lines can be on screen at once), the candidate
     * with the greatest [SubtitleCue.startMs] that is still `<= positionMs` is the one considered:
     * among the cues that could contain [positionMs], it is the most recently started one, and it
     * is found directly by the same binary search, with no extra scan.
     */
    fun cueAt(positionMs: Long): SubtitleCue? {
        val cue = cueAtOrBefore(positionMs) ?: return null
        return if (positionMs < cue.endMs) cue else null
    }

    /**
     * The cue containing [positionMs] or, when [positionMs] falls in a gap or after the last cue,
     * the last cue whose [SubtitleCue.startMs] is `<= positionMs`; `null` before the first cue or
     * for an empty track. Same binary search and same overlap rule as [cueAt].
     */
    fun cueAtOrBefore(positionMs: Long): SubtitleCue? {
        var low = 0
        var high = cues.size - 1
        var candidate = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (cues[mid].startMs <= positionMs) {
                candidate = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return if (candidate == -1) null else cues[candidate]
    }
}
