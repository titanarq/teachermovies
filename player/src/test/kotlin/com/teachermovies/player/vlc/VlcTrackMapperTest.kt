package com.teachermovies.player.vlc

import com.teachermovies.player.api.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the libVLC adapter can count on when it turns `audioTracks`/`audioTrackDescription` and
 * `spuTracks`/`spuTrackDescription` into [Track] lists: which id reaches the UI, what happens to
 * libVLC's "subtitles off" pseudo-track, and which language it can guess from the labels real
 * media carry. None of this loads libVLC, so it is a plain JVM test (#75).
 */
class VlcTrackMapperTest {
    // -- ids and names -------------------------------------------------------------------------

    @Test
    fun mapsIdsAndNamesPositionByPosition() {
        val tracks =
            VlcTrackMapper.map(
                ids = intArrayOf(1, 2),
                names = arrayOf("English [en]", "Spanish [es]"),
            )

        assertEquals(
            listOf(
                Track(id = "1", name = "English [en]", language = "en"),
                Track(id = "2", name = "Spanish [es]", language = "es"),
            ),
            tracks,
        )
    }

    @Test
    fun keepsTheLibvlcLabelAsTheTrackNameEvenWhenItCarriesNoLanguage() {
        val tracks = VlcTrackMapper.map(intArrayOf(7), arrayOf("Commentary"))

        assertEquals(listOf(Track(id = "7", name = "Commentary", language = null)), tracks)
    }

    @Test
    fun mapsIdsToTheirDecimalString() {
        val tracks = VlcTrackMapper.map(intArrayOf(0, 12), arrayOf("Track 1", "Track 2"))

        assertEquals(listOf("0", "12"), tracks.map { it.id })
    }

    @Test
    fun mapsTwoEmptyArraysToAnEmptyList() {
        assertEquals(emptyList<Track>(), VlcTrackMapper.map(intArrayOf(), emptyArray()))
    }

    @Test
    fun ignoresEntriesPastTheEndOfTheShorterArray() {
        val tracks =
            VlcTrackMapper.map(
                ids = intArrayOf(1, 2, 3),
                names = arrayOf("English [en]"),
            )

        assertEquals(listOf(Track(id = "1", name = "English [en]", language = "en")), tracks)
    }

    // -- libVLC's "subtitles off" pseudo-track --------------------------------------------------

    @Test
    fun dropsTheDisableTrackFromTheList() {
        val tracks =
            VlcTrackMapper.map(
                ids = intArrayOf(VlcTrackMapper.DISABLE_TRACK_ID, 3),
                names = arrayOf("Disable", "English [en]"),
            )

        assertEquals(listOf(Track(id = "3", name = "English [en]", language = "en")), tracks)
    }

    @Test
    fun dropsTheDisableTrackWhicheverEndOfTheListItIsOn() {
        val tracks =
            VlcTrackMapper.map(
                ids = intArrayOf(3, VlcTrackMapper.DISABLE_TRACK_ID),
                names = arrayOf("Spanish [es]", "Disable"),
            )

        assertEquals(listOf(Track(id = "3", name = "Spanish [es]", language = "es")), tracks)
    }

    @Test
    fun mapsAListThatIsOnlyTheDisableTrackToAnEmptyList() {
        val tracks = VlcTrackMapper.map(intArrayOf(VlcTrackMapper.DISABLE_TRACK_ID), arrayOf("Disable"))

        assertEquals(emptyList<Track>(), tracks)
    }

    // -- the language guess --------------------------------------------------------------------

    @Test
    fun readsTheLanguageFromABracketedTag() {
        val tracks = VlcTrackMapper.map(intArrayOf(1), arrayOf("English [en]"))

        assertEquals("en", tracks.single().language)
    }

    @Test
    fun lowercasesABracketedTagThatCarriesARegion() {
        val tracks =
            VlcTrackMapper.map(
                ids = intArrayOf(1, 2),
                names = arrayOf("Portuguese [pt-BR]", "Spanish [ES-419]"),
            )

        assertEquals(listOf("pt-br", "es-419"), tracks.map { it.language })
    }

    @Test
    fun readsTheLanguageFromABracketedLanguageName() {
        val tracks = VlcTrackMapper.map(intArrayOf(2), arrayOf("Track 2 - [Spanish]"))

        assertEquals("es", tracks.single().language)
    }

    @Test
    fun readsTheLanguageFromABareLanguageName() {
        val tracks =
            VlcTrackMapper.map(
                ids = intArrayOf(1, 2),
                names = arrayOf("French", "Track 3 - German"),
            )

        assertEquals(listOf("fr", "de"), tracks.map { it.language })
    }

    @Test
    fun reportsNoLanguageForTheLabelsLibvlcInvents() {
        val tracks =
            VlcTrackMapper.map(
                ids = intArrayOf(1, 2, 3, 4),
                names = arrayOf("Track 1", "Track 2 -", "", "   "),
            )

        assertEquals(listOf(null, null, null, null), tracks.map { it.language })
    }

    @Test
    fun reportsNoLanguageForTheTagsThatMeanNoLanguage() {
        val tracks =
            VlcTrackMapper.map(
                ids = intArrayOf(1, 2, 3),
                names = arrayOf("Undefined [und]", "[Unknown]", "Track 1 [none]"),
            )

        assertEquals(listOf(null, null, null), tracks.map { it.language })
    }

    @Test
    fun prefersTheBracketedTagOverANameItDoesNotKnow() {
        val tracks = VlcTrackMapper.map(intArrayOf(1), arrayOf("Klingon [tlh]"))

        assertEquals("tlh", tracks.single().language)
    }

    @Test
    fun reportsNoLanguageForANameOutsideTheTable() {
        val tracks = VlcTrackMapper.map(intArrayOf(1), arrayOf("[Klingon]"))

        assertNull(tracks.single().language)
    }
}
