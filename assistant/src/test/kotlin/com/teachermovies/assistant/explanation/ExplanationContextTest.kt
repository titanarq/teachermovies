package com.teachermovies.assistant.explanation

import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.assistant.subtitles.SubtitleTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExplanationContextTest {
    /** Cues `line 0`..`line n-1`, one every [stepMs], each lasting 1 s. */
    private fun track(
        n: Int,
        stepMs: Long = 2_000,
    ) = SubtitleTrack((0 until n).map { SubtitleCue(it, it * stepMs, it * stepMs + 1_000, "line $it") })

    @Test
    fun `takes three lines before and two after, oldest first`() {
        val track = track(10)
        val context = ExplanationContext.of(track, track.cues[5], "Heat", "línea cinco")
        assertEquals("line 5", context.line)
        assertEquals(listOf("line 2", "line 3", "line 4"), context.before)
        assertEquals(listOf("line 6", "line 7"), context.after)
        assertEquals("Heat", context.title)
        assertEquals("línea cinco", context.spanishLine)
    }

    @Test
    fun `fewer neighbours at the edges of the track`() {
        val track = track(3)
        assertEquals(emptyList<String>(), ExplanationContext.of(track, track.cues[0], null, null).before)
        assertEquals(listOf("line 1", "line 2"), ExplanationContext.of(track, track.cues[0], null, null).after)
        assertEquals(listOf("line 0", "line 1"), ExplanationContext.of(track, track.cues[2], null, null).before)
        assertEquals(emptyList<String>(), ExplanationContext.of(track, track.cues[2], null, null).after)
    }

    @Test
    fun `neighbours more than 20 s away are left out`() {
        // One cue every 8 s: 8 and 16 s away are in, 24 s is out.
        val track = track(10, stepMs = 8_000)
        val context = ExplanationContext.of(track, track.cues[5], null, null)
        assertEquals(listOf("line 3", "line 4"), context.before)
        assertEquals(listOf("line 6", "line 7"), context.after)
    }

    @Test
    fun `a neighbour exactly 20 s away is in, one past it is out`() {
        val cues =
            listOf(
                SubtitleCue(0, 0, 500, "far"),
                SubtitleCue(1, 999, 1_500, "edge"),
                SubtitleCue(2, 20_999, 22_000, "captured"),
                SubtitleCue(3, 40_999, 41_500, "edge after"),
                SubtitleCue(4, 41_000, 41_800, "far after"),
            )
        val context = ExplanationContext.of(SubtitleTrack(cues), cues[2], null, null)
        assertEquals(listOf("edge"), context.before)
        assertEquals(listOf("edge after"), context.after)
    }

    @Test
    fun `blank neighbours are skipped and do not use up a slot`() {
        val cues =
            listOf(
                SubtitleCue(0, 0, 500, "a"),
                SubtitleCue(1, 1_000, 1_500, "b"),
                SubtitleCue(2, 2_000, 2_500, "  "),
                SubtitleCue(3, 3_000, 3_500, "c"),
                SubtitleCue(4, 4_000, 4_500, "captured"),
                SubtitleCue(5, 5_000, 5_500, ""),
                SubtitleCue(6, 6_000, 6_500, "d"),
                SubtitleCue(7, 7_000, 7_500, "e"),
            )
        val context = ExplanationContext.of(SubtitleTrack(cues), cues[4], null, null)
        assertEquals(listOf("a", "b", "c"), context.before)
        assertEquals(listOf("d", "e"), context.after)
    }

    @Test
    fun `lines are trimmed, joined across line breaks and clipped to the hub limit`() {
        val long = "x".repeat(ExplanationContext.MAX_LINE_CHARS + 50)
        val cues =
            listOf(
                SubtitleCue(0, 0, 500, long),
                SubtitleCue(1, 1_000, 1_500, "  I'm gonna\n  make him  \r\n an offer  "),
            )
        val context = ExplanationContext.of(SubtitleTrack(cues), cues[1], "  The Godfather ", "  \n ")
        assertEquals("I'm gonna make him an offer", context.line)
        assertEquals(ExplanationContext.MAX_LINE_CHARS, context.before.single().length)
        assertEquals("The Godfather", context.title)
        assertNull(context.spanishLine)
    }

    @Test
    fun `a blank title is null`() {
        val track = track(1)
        assertNull(ExplanationContext.of(track, track.cues[0], "   ", null).title)
    }

    @Test
    fun `a cue that is not in the track gets no neighbours`() {
        val track = track(5)
        val stranger = SubtitleCue(2, 99_000, 99_500, "from another track")
        val context = ExplanationContext.of(track, stranger, null, null)
        assertEquals("from another track", context.line)
        assertEquals(emptyList<String>(), context.before)
        assertEquals(emptyList<String>(), context.after)
    }

    @Test
    fun `a span joins its cues with a space and takes neighbours around the span`() {
        val track = track(12)
        val context = ExplanationContext.of(track, track.cues.subList(5, 8), "Heat", null)
        assertEquals("line 5 line 6 line 7", context.line)
        assertEquals(listOf("line 2", "line 3", "line 4"), context.before)
        assertEquals(listOf("line 8", "line 9"), context.after)
    }

    @Test
    fun `a span of one is exactly the single cue context`() {
        val track = track(10)
        assertEquals(
            ExplanationContext.of(track, track.cues[5], "Heat", "x"),
            ExplanationContext.of(track, listOf(track.cues[5]), "Heat", "x"),
        )
    }

    @Test
    fun `a span skips blank cues and the joined line is clipped`() {
        val cues =
            listOf(
                SubtitleCue(0, 0, 500, "a".repeat(400)),
                SubtitleCue(1, 1_000, 1_500, "  "),
                SubtitleCue(2, 2_000, 2_500, "b".repeat(400)),
            )
        val context = ExplanationContext.of(SubtitleTrack(cues), cues, null, null)
        assertEquals("a".repeat(400) + " " + "b".repeat(99), context.line)
    }

    @Test
    fun `neighbours of a span respect the window from each end`() {
        val track = track(10, stepMs = 8_000)
        val context = ExplanationContext.of(track, track.cues.subList(4, 6), null, null)
        assertEquals(listOf("line 2", "line 3"), context.before)
        assertEquals(listOf("line 6", "line 7"), context.after)
    }
}
