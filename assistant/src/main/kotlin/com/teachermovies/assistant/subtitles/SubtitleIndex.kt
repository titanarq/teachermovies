package com.teachermovies.assistant.subtitles

/**
 * A cue found by stepping back through a track's cues, as [SubtitleIndex.cueLinesBefore] does.
 *
 * @property cue the cue the step landed on, already clamped at the track's first cue.
 * @property linesBack how many cues before the one the step started from [cue] really is: the
 *   requested step, or fewer when the track ran out of lines to go back through.
 */
data class SteppedCue(
    val cue: SubtitleCue,
    val linesBack: Int,
)

/**
 * Looks up the [SubtitleCue] spoken at a given playback position, by binary search over the
 * cues sorted by [SubtitleCue.startMs] -- never a linear scan, however long the track is.
 *
 * A cue covers the half-open range `[startMs, endMs)`: a position exactly at [SubtitleCue.endMs]
 * belongs to whatever comes next, not to that cue.
 */
class SubtitleIndex(
    track: SubtitleTrack,
) {
    private val cues: List<SubtitleCue> = track.cues.sortedBy { it.startMs }
    private val positions: Map<SubtitleCue, Int> = cues.mapIndexed { position, cue -> cue to position }.toMap()

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

    /**
     * The cue [lines] positions before [cue] in the sorted list (`0` gives [cue] itself), clamped at
     * the first cue: a step past it keeps resolving to it, with the smaller [SteppedCue.linesBack]
     * that implies. `null` for an empty index or a [cue] that is not part of it.
     *
     * A direct step into the sorted list, never a rescan of it.
     */
    fun cueLinesBefore(
        cue: SubtitleCue,
        lines: Int,
    ): SteppedCue? {
        val position = positions[cue] ?: return null
        val target = (position - lines).coerceAtLeast(0)
        return SteppedCue(cues[target], linesBack = position - target)
    }
}
