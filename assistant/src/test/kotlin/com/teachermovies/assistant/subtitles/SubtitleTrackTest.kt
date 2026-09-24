package com.teachermovies.assistant.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleTrackTest {
    @Test
    fun `builds a track from an ordered list of cues`() {
        val cues =
            listOf(
                SubtitleCue(index = 0, startMs = 1_000L, endMs = 4_000L, text = "Hello there."),
                SubtitleCue(index = 1, startMs = 5_000L, endMs = 6_500L, text = "Second line."),
            )

        val track = SubtitleTrack(cues)

        assertEquals(cues, track.cues)
        assertNull(track.language)
    }

    @Test
    fun `language defaults to null but can be set`() {
        val track = SubtitleTrack(cues = emptyList(), language = "en")

        assertEquals("en", track.language)
    }
}
