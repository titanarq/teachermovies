package com.teachermovies.assistant

import com.teachermovies.player.api.Track
import com.teachermovies.player.fake.FakePlayer
import java.io.File
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HiddenSubtitleControllerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val mediaFile: File
        get() = File(tempFolder.root, "movie.mkv")

    private fun writeSubtitle(
        path: String,
        text: String = SRT,
    ): File {
        val file = File(tempFolder.root, path)
        file.parentFile.mkdirs()
        file.writeText(text)
        return file
    }

    @Test
    fun `start loads the sidecar track and forces the player's own subtitles off`() =
        runTest {
            writeSubtitle("movie.en.srt")
            val player = FakePlayer()
            player.emitTracks(audio = emptyList(), subs = listOf(Track("sub-1", "English", "en")))
            player.selectSubtitle("sub-1")
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope)
            player.emitPosition(1_500L)
            runCurrent()

            val result = controller.start(mediaFile)
            runCurrent()

            assertEquals(HiddenModeResult.Started, result)
            assertTrue(controller.active.value)
            assertNull(player.selectedSubtitleId.value)
            // The track reached the engine: the cue covering the current position resolves at once.
            assertEquals("Hello there.", engine.currentSubtitle.value?.text)
        }

    @Test
    fun `while active, a subtitle track selected from outside is turned back off`() =
        runTest {
            writeSubtitle("movie.en.srt")
            val player = FakePlayer()
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope)

            controller.start(mediaFile)
            runCurrent()

            // What a default-track policy (#76) would do once the media's tracks are known.
            player.selectSubtitle("sub-1")
            runCurrent()

            assertNull(player.selectedSubtitleId.value)
            assertTrue(controller.active.value)

            // And again, for a second attempt later in the same playback.
            player.selectSubtitle("sub-2")
            runCurrent()

            assertNull(player.selectedSubtitleId.value)
        }

    @Test
    fun `currentSubtitle follows the positions the player emits while hidden mode is active`() =
        runTest {
            writeSubtitle("movie.en.srt")
            val player = FakePlayer()
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope)

            assertEquals(HiddenModeResult.Started, controller.start(mediaFile))
            runCurrent()

            player.emitPosition(1_500L)
            runCurrent()
            assertEquals("Hello there.", engine.currentSubtitle.value?.text)

            player.emitPosition(4_500L)
            runCurrent()
            assertNull(engine.currentSubtitle.value)

            player.emitPosition(5_700L)
            runCurrent()
            assertEquals("Second cue.", engine.currentSubtitle.value?.text)
        }

    @Test
    fun `start returns NoSubtitleFile and leaves the player's selection alone when nothing matches`() =
        runTest {
            writeSubtitle("other.fr.srt")
            val player = FakePlayer()
            player.selectSubtitle("sub-1")
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope)

            val result = controller.start(mediaFile)
            runCurrent()

            assertEquals(HiddenModeResult.NoSubtitleFile, result)
            assertFalse(controller.active.value)
            assertEquals("sub-1", player.selectedSubtitleId.value)
            assertNull(engine.currentSubtitle.value)
        }

    @Test
    fun `start returns Unreadable and leaves the player's selection alone when the file has no cues`() =
        runTest {
            writeSubtitle("movie.en.srt", text = "not a subtitle file at all")
            val player = FakePlayer()
            player.selectSubtitle("sub-1")
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope)

            val result = controller.start(mediaFile)
            runCurrent()

            assertTrue("was $result", result is HiddenModeResult.Unreadable)
            assertFalse(controller.active.value)
            assertEquals("sub-1", player.selectedSubtitleId.value)
            assertNull(engine.currentSubtitle.value)
        }

    @Test
    fun `stop clears the track, deactivates and stops re-asserting without re-enabling subtitles`() =
        runTest {
            writeSubtitle("movie.en.srt")
            val player = FakePlayer()
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope)

            controller.start(mediaFile)
            runCurrent()

            controller.stop()
            runCurrent()

            assertFalse(controller.active.value)
            assertNull(engine.currentSubtitle.value)
            // stop() does not turn any subtitle track back on by itself.
            assertNull(player.selectedSubtitleId.value)

            // Re-assertion is over: a selection made afterwards survives.
            player.selectSubtitle("sub-1")
            runCurrent()

            assertEquals("sub-1", player.selectedSubtitleId.value)
        }

    private companion object {
        val SRT =
            """
            1
            00:00:01,000 --> 00:00:04,000
            Hello there.

            2
            00:00:05,500 --> 00:00:06,000
            Second cue.

            """.trimIndent()
    }
}
