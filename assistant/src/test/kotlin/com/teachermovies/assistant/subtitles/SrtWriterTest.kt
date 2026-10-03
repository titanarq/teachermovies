package com.teachermovies.assistant.subtitles

import org.junit.Assert.assertEquals
import org.junit.Test

class SrtWriterTest {
    @Test
    fun `formats numbered blocks that the SRT parser reads back`() {
        val track =
            SubtitleTrack(
                listOf(
                    SubtitleCue(0, 1_500L, 3_661_007L, "Hello\n\nthere"),
                    SubtitleCue(1, 4_000L, 5_000L, "Bye."),
                ),
            )

        val text = SrtWriter.format(track)

        assertEquals(
            "1\n00:00:01,500 --> 01:01:01,007\nHello\nthere\n\n2\n00:00:04,000 --> 00:00:05,000\nBye.\n\n",
            text,
        )
    }
}
