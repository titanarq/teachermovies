package com.teachermovies.assistant.subtitles

import com.teachermovies.player.api.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedSubtitleTracksTest {
    @Test
    fun `an exact language code wins over a prefix and a name match`() {
        val tracks =
            listOf(
                Track("1", "English [en]", null),
                Track("2", "Track 2", "eng"),
                Track("3", "Track 3", "EN"),
            )

        assertEquals("3", EmbeddedSubtitleTracks.pick(tracks)?.id)
    }

    @Test
    fun `a language starting with the code wins over a name match`() {
        val tracks =
            listOf(
                Track("1", "English", null),
                Track("2", "Track 2", "ENG"),
            )

        assertEquals("2", EmbeddedSubtitleTracks.pick(tracks)?.id)
    }

    @Test
    fun `a bracketed code in the name matches`() {
        val tracks =
            listOf(
                Track("1", "Track 1 [fr]", null),
                Track("2", "Track 2 [EN]", null),
            )

        assertEquals("2", EmbeddedSubtitleTracks.pick(tracks)?.id)
    }

    @Test
    fun `the language's English name in the track name matches`() {
        val tracks =
            listOf(
                Track("1", "Francais", "fr"),
                Track("2", "Subtitles - ENGLISH (SDH)", null),
            )

        assertEquals("2", EmbeddedSubtitleTracks.pick(tracks)?.id)
    }

    @Test
    fun `ties are broken by list order`() {
        val tracks =
            listOf(
                Track("1", "Spanish", "es"),
                Track("2", "English forced", "en"),
                Track("3", "English full", "en"),
            )

        assertEquals("2", EmbeddedSubtitleTracks.pick(tracks)?.id)
    }

    @Test
    fun `a list without English tracks gives null`() {
        val tracks =
            listOf(
                Track("1", "Espanol", "spa"),
                Track("2", "Francais [fr]", null),
                Track("3", "Track 3", null),
            )

        assertNull(EmbeddedSubtitleTracks.pick(tracks))
    }

    @Test
    fun `another requested language is honoured`() {
        val tracks =
            listOf(
                Track("1", "English", "en"),
                Track("2", "Spanish", "spa"),
            )

        assertEquals("2", EmbeddedSubtitleTracks.pick(tracks, language = "es")?.id)
    }

    @Test
    fun `an empty list gives null`() {
        assertNull(EmbeddedSubtitleTracks.pick(emptyList()))
    }
}
