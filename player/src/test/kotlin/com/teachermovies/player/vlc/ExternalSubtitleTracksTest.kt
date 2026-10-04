package com.teachermovies.player.vlc

import com.teachermovies.player.api.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The file-name side of external subtitle tracks (#373): which track is a slave and what language it is. */
class ExternalSubtitleTracksTest {
    private val en = File("/m/subs/X.en.opensubtitles.srt")
    private val es = File("/m/subs/X.es.opensubtitles.srt")

    @Test
    fun downloadedFilesGetTheirLanguageAndAnOpenSubtitlesName() {
        val tracks =
            ExternalSubtitleTracks.mark(
                listOf(Track("3", "English [en]", "en"), Track("7", "English", "en"), Track("8", "English", "en")),
                listOf(en, es),
            )

        assertEquals(Track("3", "English [en]", "en", external = false), tracks[0])
        assertEquals(Track("7", "OpenSubtitles (en)", "en", external = true), tracks[1])
        assertEquals(Track("8", "OpenSubtitles (es)", "es", external = true), tracks[2])
    }

    @Test
    fun containerTracksKeepTheirLibvlcLanguage() {
        val tracks = ExternalSubtitleTracks.mark(listOf(Track("1", "Spanish [es]", "es")), emptyList())

        assertEquals(listOf(Track("1", "Spanish [es]", "es")), tracks)
        assertFalse(tracks.single().external)
    }

    @Test
    fun onlyTheSlavesPublishedSoFarAreMarkedWhenTheContainerTracksAreStillMissing() {
        val tracks = ExternalSubtitleTracks.mark(listOf(Track("7", "English", "en")), listOf(en, es))

        assertTrue(tracks.single().external)
        assertEquals("en", tracks.single().language)
    }

    @Test
    fun plainLanguageSuffixIsReadButKeepsTheLibvlcName() {
        val track =
            ExternalSubtitleTracks
                .mark(
                    listOf(Track("4", "Movie.es", null)),
                    listOf(File("/m/Movie.es.srt")),
                ).single()

        assertEquals(Track("4", "Movie.es", "es", external = true), track)
    }

    @Test
    fun aFileNameWithoutALanguageFallsBackToLibvlcs() {
        assertNull(ExternalSubtitleTracks.languageOf(File("/m/The.Big.srt")))
        assertNull(ExternalSubtitleTracks.languageOf(File("/m/Movie.srt")))
        val track =
            ExternalSubtitleTracks
                .mark(
                    listOf(Track("4", "English", "en")),
                    listOf(File("/m/Movie.srt")),
                ).single()
        assertEquals("en", track.language)
        assertTrue(track.external)
    }

    @Test
    fun sanitisedDownloadNamesStillYieldTheLanguage() {
        val file = File("/m/subs/TheTrumanShow1998REMASTERED1080pBluRayHEVCx2655.1BONE.es.opensubtitles.srt")

        assertEquals("es", ExternalSubtitleTracks.languageOf(file))
    }
}
