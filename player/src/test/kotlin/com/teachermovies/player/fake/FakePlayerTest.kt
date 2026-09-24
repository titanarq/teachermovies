package com.teachermovies.player.fake

import com.teachermovies.player.api.PlayerState
import com.teachermovies.player.api.Track
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The fake is the `Player` every test above `:player` programs against, so what it does here is the
 * contract those tests rest on: what [FakePlayer.open] starts from, how the transport controls move
 * [PlayerState], that seeking stays inside the media, and how internal and external tracks get
 * selected.
 */
class FakePlayerTest {
    private val player = FakePlayer()
    private val movie = File("/volume/Movies/a1b2c3/Movie.2023.1080p.mkv")

    @Test
    fun startsIdleWithNoMediaAndNoTracks() {
        assertEquals(PlayerState.Idle, player.state.value)
        assertEquals(0L, player.positionMs.value)
        assertEquals(0L, player.durationMs.value)
        assertEquals(emptyList<Track>(), player.audioTracks.value)
        assertEquals(emptyList<Track>(), player.subtitleTracks.value)
        assertNull(player.selectedAudioId.value)
        assertNull(player.selectedSubtitleId.value)
    }

    @Test
    fun openStartsOpeningAtThePositionItIsGiven() {
        player.open(movie, startPositionMs = 95_000L)

        assertEquals(PlayerState.Opening, player.state.value)
        assertEquals(95_000L, player.positionMs.value)
        assertEquals(0L, player.durationMs.value)
    }

    @Test
    fun openStartsFromTheBeginningOfTheFileByDefault() {
        player.open(movie)

        assertEquals(PlayerState.Opening, player.state.value)
        assertEquals(0L, player.positionMs.value)
    }

    @Test
    fun playAndPauseMoveTheStateDirectly() {
        player.open(movie)
        player.play()
        assertEquals(PlayerState.Playing, player.state.value)

        player.pause()
        assertEquals(PlayerState.Paused, player.state.value)
    }

    @Test
    fun togglePlayPauseSwitchesBetweenPlayingAndPaused() {
        player.open(movie)
        player.play()

        player.togglePlayPause()
        assertEquals(PlayerState.Paused, player.state.value)

        player.togglePlayPause()
        assertEquals(PlayerState.Playing, player.state.value)
    }

    @Test
    fun togglePlayPauseDoesNothingWhileIdle() {
        player.togglePlayPause()

        assertEquals(PlayerState.Idle, player.state.value)
    }

    @Test
    fun togglePlayPauseDoesNothingOnceTheMediaEnded() {
        player.open(movie)
        player.play()
        player.emitDuration(90_000L)
        player.end()

        player.togglePlayPause()

        assertEquals(PlayerState.Ended, player.state.value)
    }

    @Test
    fun togglePlayPauseDoesNothingAfterAFailure() {
        player.open(movie)
        player.fail("no decoder for HEVC")

        player.togglePlayPause()

        assertEquals(PlayerState.Error("no decoder for HEVC"), player.state.value)
    }

    @Test
    fun seekToStaysInsideTheMedia() {
        player.emitDuration(120_000L)

        player.seekTo(45_000L)
        assertEquals(45_000L, player.positionMs.value)

        player.seekTo(-5_000L)
        assertEquals(0L, player.positionMs.value)

        player.seekTo(500_000L)
        assertEquals(120_000L, player.positionMs.value)
    }

    @Test
    fun seekByIsRelativeToTheCurrentPositionAndClamped() {
        player.emitDuration(120_000L)
        player.emitPosition(60_000L)

        player.seekBy(-15_000L)
        assertEquals(45_000L, player.positionMs.value)

        player.seekBy(-60_000L)
        assertEquals(0L, player.positionMs.value)

        player.seekBy(10_000L)
        player.seekBy(300_000L)
        assertEquals(120_000L, player.positionMs.value)
    }

    @Test
    fun seekingIsClampedToZeroWhileTheDurationIsUnknown() {
        player.emitPosition(30_000L)

        player.seekBy(10_000L)
        assertEquals(0L, player.positionMs.value)
    }

    @Test
    fun emitTracksReplacesBothTrackLists() {
        val audio = listOf(Track("a1", "English 5.1", "eng"), Track("a2", "Castellano", "spa"))
        val subs = listOf(Track("s1", "English", "eng"))

        player.emitTracks(audio = audio, subs = subs)

        assertEquals(audio, player.audioTracks.value)
        assertEquals(subs, player.subtitleTracks.value)
    }

    @Test
    fun selectsTheAudioAndSubtitleTrackItIsAskedFor() {
        player.emitTracks(
            audio = listOf(Track("a1", "English 5.1", "eng"), Track("a2", "Castellano", "spa")),
            subs = listOf(Track("s1", "English", "eng"), Track("s2", "Castellano", "spa")),
        )

        player.selectAudio("a2")
        player.selectSubtitle("s1")

        assertEquals("a2", player.selectedAudioId.value)
        assertEquals("s1", player.selectedSubtitleId.value)
    }

    @Test
    fun selectingNoSubtitleTurnsSubtitlesOffAndKeepsTheTracks() {
        val subs = listOf(Track("s1", "English", "eng"))
        player.emitTracks(audio = emptyList(), subs = subs)
        player.selectSubtitle("s1")

        player.selectSubtitle(null)

        assertNull(player.selectedSubtitleId.value)
        assertEquals(subs, player.subtitleTracks.value)
    }

    @Test
    fun addExternalSubtitleAppendsATrackAndSelectsIt() {
        player.emitTracks(audio = emptyList(), subs = listOf(Track("s1", "English", "eng")))

        player.addExternalSubtitle(File("/sdcard/Download/Movie.eng.srt"), select = true)

        assertEquals(
            listOf(
                Track("s1", "English", "eng"),
                Track("ext:Movie.eng.srt", "Movie.eng.srt", null),
            ),
            player.subtitleTracks.value,
        )
        assertEquals("ext:Movie.eng.srt", player.selectedSubtitleId.value)
    }

    @Test
    fun addExternalSubtitleWithoutSelectKeepsTheCurrentSelection() {
        player.emitTracks(audio = emptyList(), subs = listOf(Track("s1", "English", "eng")))
        player.selectSubtitle("s1")

        player.addExternalSubtitle(File("/sdcard/Download/Movie.spa.srt"), select = false)

        assertEquals(
            listOf(
                Track("s1", "English", "eng"),
                Track("ext:Movie.spa.srt", "Movie.spa.srt", null),
            ),
            player.subtitleTracks.value,
        )
        assertEquals("s1", player.selectedSubtitleId.value)
    }

    @Test
    fun endMovesToTheEndOfTheMedia() {
        player.open(movie)
        player.play()
        player.emitDuration(90_000L)

        player.end()

        assertEquals(PlayerState.Ended, player.state.value)
        assertEquals(90_000L, player.positionMs.value)
    }

    @Test
    fun failCarriesTheMessageItIsGiven() {
        player.open(movie)
        player.play()

        player.fail("file disappeared")

        assertEquals(PlayerState.Error("file disappeared"), player.state.value)
    }

    @Test
    fun releaseGoesBackToIdleAndKeepsWhatTheMediaReported() {
        player.open(movie)
        player.play()
        player.emitDuration(90_000L)
        player.emitTracks(audio = listOf(Track("a1", "English 5.1", "eng")), subs = emptyList())
        player.selectAudio("a1")

        player.release()

        assertEquals(PlayerState.Idle, player.state.value)
        assertEquals(90_000L, player.durationMs.value)
        assertEquals(listOf(Track("a1", "English 5.1", "eng")), player.audioTracks.value)
        assertEquals("a1", player.selectedAudioId.value)
    }
}
