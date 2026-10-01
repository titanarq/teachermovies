package com.teachermovies.assistant.fake

import com.teachermovies.assistant.SpanishTextSource
import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.assistant.subtitles.SubtitleIndex
import com.teachermovies.assistant.subtitles.SubtitleTrack

/**
 * In-memory [SpanishTextSource] for every test above `:assistant` (ADR-0003: fakes live in the main
 * source set), standing in for the real Spanish timeline until it exists.
 *
 * [textAt] answers with the text of the [SubtitleCue] of `lines` covering the position -- `null` in
 * a gap, before the first line and after the last one, the same half-open `[startMs, endMs)` rule a
 * real timeline follows -- and records every position it was asked about in [queries].
 */
class FakeSpanishTextSource(
    lines: List<SubtitleCue> = emptyList(),
) : SpanishTextSource {
    private val index = SubtitleIndex(SubtitleTrack(cues = lines))
    private val recordedQueries = mutableListOf<Long>()

    /** Every position [textAt] was called with, in call order. */
    val queries: List<Long>
        get() = recordedQueries.toList()

    override fun textAt(positionMs: Long): String? {
        recordedQueries += positionMs
        return index.cueAt(positionMs)?.text
    }
}
