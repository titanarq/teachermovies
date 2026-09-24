package com.teachermovies.assistant.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleIndexTest {
    private fun cue(
        index: Int,
        startMs: Long,
        endMs: Long,
        text: String = "cue-$index",
    ) = SubtitleCue(index = index, startMs = startMs, endMs = endMs, text = text)

    @Test
    fun `cueAt returns null for an empty track`() {
        val index = SubtitleIndex(SubtitleTrack(cues = emptyList()))

        assertNull(index.cueAt(0L))
        assertNull(index.cueAt(1_000L))
    }

    @Test
    fun `cueAt returns null before the first cue`() {
        val track = SubtitleTrack(cues = listOf(cue(0, 1_000L, 2_000L)))
        val index = SubtitleIndex(track)

        assertNull(index.cueAt(0L))
        assertNull(index.cueAt(999L))
    }

    @Test
    fun `cueAt returns null after the last cue`() {
        val track = SubtitleTrack(cues = listOf(cue(0, 1_000L, 2_000L)))
        val index = SubtitleIndex(track)

        assertNull(index.cueAt(2_000L))
        assertNull(index.cueAt(5_000L))
    }

    @Test
    fun `cueAt returns null in a gap between cues`() {
        val track =
            SubtitleTrack(
                cues =
                    listOf(
                        cue(0, 1_000L, 2_000L),
                        cue(1, 3_000L, 4_000L),
                    ),
            )
        val index = SubtitleIndex(track)

        assertNull(index.cueAt(2_500L))
    }

    @Test
    fun `cueAt matches the exact start boundary`() {
        val first = cue(0, 1_000L, 2_000L)
        val second = cue(1, 3_000L, 4_000L)
        val index = SubtitleIndex(SubtitleTrack(cues = listOf(first, second)))

        assertEquals(first, index.cueAt(1_000L))
        assertEquals(second, index.cueAt(3_000L))
    }

    @Test
    fun `cueAt excludes the exact end boundary`() {
        val first = cue(0, 1_000L, 2_000L)
        val index = SubtitleIndex(SubtitleTrack(cues = listOf(first)))

        assertNull(index.cueAt(2_000L))
        assertEquals(first, index.cueAt(1_999L))
    }

    @Test
    fun `cueAt resolves the boundary shared by two adjacent cues to the one starting there`() {
        val first = cue(0, 1_000L, 2_000L)
        val second = cue(1, 2_000L, 3_000L)
        val index = SubtitleIndex(SubtitleTrack(cues = listOf(first, second)))

        assertEquals(second, index.cueAt(2_000L))
    }

    @Test
    fun `cueAt prefers the greatest startMs among overlapping cues that contain the position`() {
        val first = cue(0, 1_000L, 4_000L)
        val second = cue(1, 2_000L, 5_000L)
        val index = SubtitleIndex(SubtitleTrack(cues = listOf(first, second)))

        assertEquals(first, index.cueAt(1_500L))
        assertEquals(second, index.cueAt(2_000L))
        assertEquals(second, index.cueAt(3_500L))
    }

    @Test
    fun `cueAt works regardless of the order cues were passed in`() {
        val early = cue(0, 1_000L, 2_000L)
        val late = cue(1, 5_000L, 6_000L)
        val index = SubtitleIndex(SubtitleTrack(cues = listOf(late, early)))

        assertEquals(early, index.cueAt(1_500L))
        assertEquals(late, index.cueAt(5_500L))
    }
}
