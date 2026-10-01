package com.teachermovies.assistant.explanation

import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.assistant.subtitles.SubtitleTrack

/**
 * Everything Claude is given to explain one captured line (ADR-0005 §7): the movie [title] when
 * known, the captured English [line], up to [MAX_BEFORE] English lines [before] it and [MAX_AFTER]
 * [after] it (oldest first, both), and the time-aligned Spanish line [spanishLine] when there is one.
 * It is the explain job's payload one to one (#275's `BridgeJob.Explain`), so the adapter wiring
 * installs copies fields and adds nothing.
 */
data class ExplanationContext(
    val title: String?,
    val line: String,
    val before: List<String>,
    val after: List<String>,
    val spanishLine: String?,
) {
    companion object {
        /** English lines before the captured one that go with it (ADR-0005 §7). */
        const val MAX_BEFORE = 3

        /** English lines after the captured one that go with it (ADR-0005 §7). */
        const val MAX_AFTER = 2

        /**
         * How far, start to start, a neighbouring cue may be from the captured one and still count as
         * context: a line from another scene explains nothing and costs prompt size.
         */
        const val WINDOW_MS = 20_000L

        /**
         * Longest line the job hub accepts (`BridgeJobProtocol.MAX_LINE_CHARS`); a longer one would be
         * refused as a whole, so each line is clipped to it instead.
         */
        const val MAX_LINE_CHARS = 500

        /** The context of the single [cue]: [of] over a span of one. */
        fun of(
            track: SubtitleTrack,
            cue: SubtitleCue,
            title: String?,
            spanishLine: String?,
        ): ExplanationContext = of(track, listOf(cue), title, spanishLine)

        /**
         * The context of [cues], the N consecutive cues of [track] ending at the captured one, oldest
         * first (matched by [SubtitleCue.index], the cue's position in its track). The line is their
         * non-blank texts joined with a single space and clipped to [MAX_LINE_CHARS]; the neighbours
         * are the nearest non-blank cues on each side of that span whose start is at most [WINDOW_MS]
         * from the span's first cue (before) or last cue (after), at most [MAX_BEFORE] before and
         * [MAX_AFTER] after. A span end that is not in [track] (the track was reloaded under it) gets
         * no neighbours on its side rather than wrong ones. A blank [title] or [spanishLine] is null.
         * Every line is trimmed and its inner line breaks joined with a space; one cue gives exactly
         * the context of that cue alone.
         */
        fun of(
            track: SubtitleTrack,
            cues: List<SubtitleCue>,
            title: String?,
            spanishLine: String?,
        ): ExplanationContext {
            require(cues.isNotEmpty()) { "at least one cue to explain" }
            val first = cues.first()
            val last = cues.last()
            val firstPosition = first.index.takeIf { track.cues.getOrNull(it) == first }
            val lastPosition = last.index.takeIf { track.cues.getOrNull(it) == last }
            val before =
                if (firstPosition == null) {
                    emptyList()
                } else {
                    neighbours(first, (firstPosition - 1 downTo 0).asSequence().map { track.cues[it] }, MAX_BEFORE)
                        .reversed()
                }
            val after =
                if (lastPosition == null) {
                    emptyList()
                } else {
                    val outward = (lastPosition + 1 until track.cues.size).asSequence().map { track.cues[it] }
                    neighbours(last, outward, MAX_AFTER)
                }
            return ExplanationContext(
                title = title?.let(::clean)?.takeIf { it.isNotEmpty() },
                line =
                    cues
                        .map { clean(it.text) }
                        .filter { it.isNotEmpty() }
                        .joinToString(" ")
                        .take(MAX_LINE_CHARS),
                before = before,
                after = after,
                spanishLine = spanishLine?.let(::clean)?.takeIf { it.isNotEmpty() },
            )
        }

        /** The first [max] non-blank lines of [outward] (nearest first) inside the window of [cue]. */
        private fun neighbours(
            cue: SubtitleCue,
            outward: Sequence<SubtitleCue>,
            max: Int,
        ): List<String> =
            outward
                .takeWhile { kotlin.math.abs(it.startMs - cue.startMs) <= WINDOW_MS }
                .map { clean(it.text) }
                .filter { it.isNotEmpty() }
                .take(max)
                .toList()

        private fun clean(text: String): String = text.trim().replace(LINE_BREAKS, " ").take(MAX_LINE_CHARS)

        private val LINE_BREAKS = Regex("\\s*\\R\\s*")
    }
}
