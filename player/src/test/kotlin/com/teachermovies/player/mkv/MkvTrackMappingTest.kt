package com.teachermovies.player.mkv

import com.teachermovies.player.api.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MkvTrackMappingTest {
    private val mkvTracks =
        listOf(
            MkvTextTrack(3, "S_TEXT/UTF8", "eng", null, "English", null),
            MkvTextTrack(5, "S_TEXT/ASS", "spa", null, "Spanish", null),
        )

    @Test
    fun aNumericIdEqualToATrackNumberWins() {
        // Listed in the opposite order to the file: the TrackNumber decides, not the position.
        val vlc = listOf(Track("5", "Spanish - [Spanish]", "spa"), Track("3", "English - [English]", "eng"))

        assertEquals(5L, MkvTrackMapping.resolve("5", vlc, mkvTracks))
        assertEquals(3L, MkvTrackMapping.resolve("3", vlc, mkvTracks))
    }

    @Test
    fun otherwiseTheNthEmbeddedTrackMapsToTheNthMatroskaTrack() {
        val vlc = listOf(Track("0", "Track 1", null), Track("1", "Track 2", null))

        assertEquals(3L, MkvTrackMapping.resolve("0", vlc, mkvTracks))
        assertEquals(5L, MkvTrackMapping.resolve("1", vlc, mkvTracks))
    }

    @Test
    fun anExternalSubtitleListedAfterTheEmbeddedOnesIsNotMapped() {
        val vlc =
            listOf(
                Track("3", "English", "eng"),
                Track("5", "Spanish", "spa"),
                Track("12", "Movie.en.srt", null),
            )

        assertNull(MkvTrackMapping.resolve("12", vlc, mkvTracks))
    }

    @Test
    fun anUnknownIdIsNotMapped() {
        val vlc = listOf(Track("3", "English", "eng"), Track("5", "Spanish", "spa"))

        assertNull(MkvTrackMapping.resolve("7", vlc, mkvTracks))
        assertNull(MkvTrackMapping.resolve("3", vlc, emptyList()))
    }
}
