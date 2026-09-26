package com.teachermovies.assistant

import com.teachermovies.player.api.SubtitleExtraction
import com.teachermovies.player.api.SubtitleFormat
import com.teachermovies.player.api.Track
import com.teachermovies.player.fake.FakePlayer
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HiddenSubtitleControllerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val mediaFile: File
        get() = File(tempFolder.root, "movie.mkv")

    private val cacheDir: File
        get() = File(tempFolder.root, "cache")

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
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)
            player.emitPosition(1_500L)
            runCurrent()

            val result = controller.start(mediaFile)
            runCurrent()

            assertEquals(HiddenModeResult.Started(SubtitleSource.SIDECAR), result)
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
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)

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

    // -- The viewer's own choice in the tracks panel (#227) --

    @Test
    fun `a subtitle track the viewer picks is kept while hidden mode stays active`() =
        runTest {
            writeSubtitle("movie.en.srt")
            val player = FakePlayer()
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)
            controller.start(mediaFile)
            runCurrent()

            controller.selectByViewer("sub-2")
            runCurrent()

            assertEquals("sub-2", player.selectedSubtitleId.value)
            assertTrue(controller.active.value)

            // Another choice later in the same movie is honoured too, including "Desactivados".
            controller.selectByViewer(null)
            runCurrent()
            assertNull(player.selectedSubtitleId.value)
            controller.selectByViewer("sub-1")
            runCurrent()
            assertEquals("sub-1", player.selectedSubtitleId.value)
        }

    @Test
    fun `a policy selection before any viewer choice is still reverted`() =
        runTest {
            writeSubtitle("movie.en.srt")
            val player = FakePlayer()
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)
            controller.start(mediaFile)
            runCurrent()

            // A default-track policy selects directly on the player, not through selectByViewer.
            player.selectSubtitle("sub-1")
            runCurrent()

            assertNull(player.selectedSubtitleId.value)
        }

    // -- The viewer's choice persisted from an earlier session (#248) --

    @Test
    fun `a persisted viewer choice already applied is left on by start`() =
        runTest {
            writeSubtitle("movie.en.srt")
            val player = FakePlayer()
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)
            // The session's track policy re-applied the stored choice before hidden mode started.
            player.selectSubtitle("sub-2")

            val result = controller.start(mediaFile, viewerSubtitleId = "sub-2")
            runCurrent()

            assertEquals(HiddenModeResult.Started(SubtitleSource.SIDECAR), result)
            assertTrue(controller.active.value)
            assertEquals("sub-2", player.selectedSubtitleId.value)
        }

    @Test
    fun `a persisted viewer choice applied after start is kept, any other track is reverted`() =
        runTest {
            writeSubtitle("movie.en.srt")
            val player = FakePlayer()
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)
            // A default track selected before the stored choice is re-applied is still turned off.
            player.selectSubtitle("sub-1")

            controller.start(mediaFile, viewerSubtitleId = "sub-2")
            runCurrent()
            assertNull(player.selectedSubtitleId.value)

            player.selectSubtitle("sub-2")
            runCurrent()
            assertEquals("sub-2", player.selectedSubtitleId.value)

            player.selectSubtitle("sub-1")
            runCurrent()
            assertNull(player.selectedSubtitleId.value)
            assertTrue(controller.active.value)
        }

    @Test
    fun `a default track the player reports after the persisted choice goes back to that choice, not off`() =
        runTest {
            // #248 (emulator, reopen without a restart): libVLC briefly reads back its own default
            // subtitle after the session asked for the stored one; turning it off would cancel both.
            writeSubtitle("movie.en.srt")
            val player = FakePlayer()
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)
            controller.start(mediaFile, viewerSubtitleId = "sub-2")
            runCurrent()
            player.emitTracks(
                audio = emptyList(),
                subs = listOf(Track("sub-1", "English", "en"), Track("sub-2", "", "es")),
            )
            player.selectSubtitle("sub-2")
            runCurrent()

            player.selectSubtitle("sub-1")
            runCurrent()

            assertEquals("sub-2", player.selectedSubtitleId.value)
            assertTrue(controller.active.value)
        }

    @Test
    fun `a default track is turned off when the persisted choice is not one of the player's tracks`() =
        runTest {
            writeSubtitle("movie.en.srt")
            val player = FakePlayer()
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)
            player.emitTracks(audio = emptyList(), subs = listOf(Track("sub-1", "English", "en")))
            controller.start(mediaFile, viewerSubtitleId = "sub-9")
            runCurrent()

            player.selectSubtitle("sub-1")
            runCurrent()

            assertNull(player.selectedSubtitleId.value)
        }

    @Test
    fun `after a viewer choice the assistant still reads and captures lines`() =
        runTest {
            writeSubtitle("movie.en.srt")
            val player = FakePlayer()
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)
            val capture = LineCaptureController(player, engine, backgroundScope)
            controller.start(mediaFile)
            runCurrent()
            controller.selectByViewer("sub-1")
            runCurrent()

            player.play()
            player.emitPosition(1_500L)
            runCurrent()

            assertEquals("Hello there.", engine.currentSubtitle.value?.text)
            assertEquals(CaptureResult.Captured, capture.capture())
            assertEquals(
                "Hello there.",
                capture.captured.value
                    ?.cue
                    ?.text,
            )
            assertEquals("sub-1", player.selectedSubtitleId.value)
        }

    @Test
    fun `the next movie's start forces subtitles off again after a viewer choice`() =
        runTest {
            writeSubtitle("movie.en.srt")
            val player = FakePlayer()
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)
            controller.start(mediaFile)
            runCurrent()
            controller.selectByViewer("sub-1")
            runCurrent()

            controller.start(mediaFile)
            runCurrent()
            assertNull(player.selectedSubtitleId.value)

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
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)

            assertEquals(HiddenModeResult.Started(SubtitleSource.SIDECAR), controller.start(mediaFile))
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
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)

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
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)

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
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)

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

    @Test
    fun `a sidecar file wins over an embedded English track`() =
        runTest {
            writeSubtitle("movie.en.srt")
            val player = FakePlayer()
            player.emitTracks(audio = emptyList(), subs = listOf(Track("sub-1", "English", "en")))
            player.emitExtractionText(OTHER_SRT, SubtitleFormat.SRT)
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)
            player.emitPosition(1_500L)
            runCurrent()

            val result = controller.start(mediaFile)
            runCurrent()

            assertEquals(HiddenModeResult.Started(SubtitleSource.SIDECAR), result)
            assertEquals("Hello there.", engine.currentSubtitle.value?.text)
            assertTrue(player.extractionCalls.isEmpty())
        }

    @Test
    fun `without a sidecar the embedded English track is extracted and followed`() =
        runTest {
            val player = FakePlayer()
            player.emitTracks(
                audio = emptyList(),
                subs = listOf(Track("sub-fr", "Francais", "fr"), Track("sub-en", "English", "eng")),
            )
            player.selectSubtitle("sub-en")
            player.emitExtractionText(SRT, SubtitleFormat.SRT)
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)

            val result = controller.start(mediaFile)
            runCurrent()

            assertEquals(HiddenModeResult.Started(SubtitleSource.EMBEDDED), result)
            assertTrue(controller.active.value)
            assertNull(player.selectedSubtitleId.value)
            assertEquals(listOf("sub-en"), player.extractionCalls)
            val cached = File(cacheDir, "subtitles/movie.sub-en.srt")
            assertTrue(cached.isFile && cached.length() > 0)

            player.emitPosition(1_500L)
            runCurrent()
            assertEquals("Hello there.", engine.currentSubtitle.value?.text)

            player.emitPosition(5_700L)
            runCurrent()
            assertEquals("Second cue.", engine.currentSubtitle.value?.text)

            // Re-assertion works the same as for a sidecar.
            player.selectSubtitle("sub-fr")
            runCurrent()
            assertNull(player.selectedSubtitleId.value)
        }

    @Test
    fun `a second start for the same media reuses the cache file`() =
        runTest {
            val player = FakePlayer()
            player.emitTracks(audio = emptyList(), subs = listOf(Track("sub-1", "English", "en")))
            player.emitExtractionText(ASS, SubtitleFormat.ASS)
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)

            assertEquals(HiddenModeResult.Started(SubtitleSource.EMBEDDED), controller.start(mediaFile))
            controller.stop()
            assertTrue(File(cacheDir, "subtitles/movie.sub-1.ass").isFile)

            // A later extraction would fail: the cached file must be what the second start reads.
            player.emitExtraction(SubtitleExtraction.Failed("must not be called"))
            player.emitPosition(1_500L)
            runCurrent()

            assertEquals(HiddenModeResult.Started(SubtitleSource.EMBEDDED), controller.start(mediaFile))
            runCurrent()

            assertEquals(listOf("sub-1"), player.extractionCalls)
            assertEquals("From ASS.", engine.currentSubtitle.value?.text)
        }

    @Test
    fun `a failed extraction gives Unreadable and leaves the player's selection alone`() =
        runTest {
            val player = FakePlayer()
            player.emitTracks(audio = emptyList(), subs = listOf(Track("sub-1", "English", "en")))
            player.selectSubtitle("sub-1")
            player.emitExtraction(SubtitleExtraction.Failed("corrupt cluster"))
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)

            val result = controller.start(mediaFile)
            runCurrent()

            assertTrue("was $result", result is HiddenModeResult.Unreadable)
            assertTrue((result as HiddenModeResult.Unreadable).reason.contains("failed"))
            assertFalse(controller.active.value)
            assertEquals("sub-1", player.selectedSubtitleId.value)
            assertNull(engine.currentSubtitle.value)
        }

    @Test
    fun `an image-based embedded track gives Unreadable naming it`() =
        runTest {
            val player = FakePlayer()
            player.emitTracks(audio = emptyList(), subs = listOf(Track("sub-1", "English PGS", "en")))
            player.emitExtraction(SubtitleExtraction.NotTextBased)
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)

            val result = controller.start(mediaFile)

            assertTrue("was $result", result is HiddenModeResult.Unreadable)
            assertTrue((result as HiddenModeResult.Unreadable).reason.contains("image-based"))
            assertFalse(controller.active.value)
        }

    @Test
    fun `an extracted track without cues gives Unreadable`() =
        runTest {
            val player = FakePlayer()
            player.emitTracks(audio = emptyList(), subs = listOf(Track("sub-1", "English", "en")))
            player.emitExtractionText("no cues here", SubtitleFormat.SRT)
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)

            val result = controller.start(mediaFile)

            assertTrue("was $result", result is HiddenModeResult.Unreadable)
            assertFalse(controller.active.value)
        }

    @Test
    fun `no sidecar and no English embedded track gives NoSubtitleFile`() =
        runTest {
            val player = FakePlayer()
            player.emitTracks(audio = emptyList(), subs = listOf(Track("sub-1", "Espanol", "spa")))
            player.selectSubtitle("sub-1")
            player.emitExtractionText(SRT, SubtitleFormat.SRT)
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)

            val result = controller.start(mediaFile)
            runCurrent()

            assertEquals(HiddenModeResult.NoSubtitleFile, result)
            assertFalse(controller.active.value)
            assertEquals("sub-1", player.selectedSubtitleId.value)
            assertTrue(player.extractionCalls.isEmpty())
        }

    @Test
    fun `embedded tracks published after start began are waited for and used`() =
        runTest {
            val player = FakePlayer()
            player.emitExtractionText(SRT, SubtitleFormat.SRT)
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)
            val publishAfterMs = HiddenSubtitleController.TRACKS_TIMEOUT_MS / 2
            // What libVLC does: the tracks show up only once the media has been parsed.
            backgroundScope.launch {
                delay(publishAfterMs)
                player.emitTracks(audio = emptyList(), subs = listOf(Track("sub-en", "English", "en")))
            }
            val startedAt = currentTime

            val result = controller.start(mediaFile)

            assertEquals(HiddenModeResult.Started(SubtitleSource.EMBEDDED), result)
            assertEquals(publishAfterMs, currentTime - startedAt)
            assertEquals(listOf("sub-en"), player.extractionCalls)
            assertTrue(controller.active.value)
        }

    @Test
    fun `no tracks published within the timeout gives NoSubtitleFile`() =
        runTest {
            val player = FakePlayer()
            player.emitExtractionText(SRT, SubtitleFormat.SRT)
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)
            // Too late: published after the wait is over.
            backgroundScope.launch {
                delay(HiddenSubtitleController.TRACKS_TIMEOUT_MS + 1_000L)
                player.emitTracks(audio = emptyList(), subs = listOf(Track("sub-en", "English", "en")))
            }
            val startedAt = currentTime

            val result = controller.start(mediaFile)

            assertEquals(HiddenModeResult.NoSubtitleFile, result)
            assertEquals(HiddenSubtitleController.TRACKS_TIMEOUT_MS, currentTime - startedAt)
            assertFalse(controller.active.value)
            assertTrue(player.extractionCalls.isEmpty())
        }

    @Test
    fun `published tracks without English give NoSubtitleFile without waiting`() =
        runTest {
            val player = FakePlayer()
            player.emitTracks(audio = emptyList(), subs = listOf(Track("sub-fr", "Francais", "fr")))
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)
            val startedAt = currentTime

            val result = controller.start(mediaFile)

            assertEquals(HiddenModeResult.NoSubtitleFile, result)
            assertEquals(0L, currentTime - startedAt)
            assertTrue(player.extractionCalls.isEmpty())
        }

    @Test
    fun `tracks already known when start runs are used without waiting`() =
        runTest {
            val player = FakePlayer()
            player.emitTracks(audio = emptyList(), subs = listOf(Track("sub-en", "English", "en")))
            player.emitExtractionText(SRT, SubtitleFormat.SRT)
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)
            val startedAt = currentTime

            val result = controller.start(mediaFile)

            assertEquals(HiddenModeResult.Started(SubtitleSource.EMBEDDED), result)
            assertEquals(0L, currentTime - startedAt)
            assertEquals(listOf("sub-en"), player.extractionCalls)
        }

    @Test
    fun `stop while start waits for tracks ends the wait with NoSubtitleFile`() =
        runTest {
            val player = FakePlayer()
            player.emitExtractionText(SRT, SubtitleFormat.SRT)
            val engine = SubtitleEngine(player.positionMs, backgroundScope)
            val controller = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)

            val pending = async { controller.start(mediaFile) }
            runCurrent()
            assertFalse(pending.isCompleted)
            val stoppedAt = currentTime

            controller.stop()
            runCurrent()

            assertTrue(pending.isCompleted)
            assertEquals(HiddenModeResult.NoSubtitleFile, pending.await())
            assertEquals(stoppedAt, currentTime)
            // Tracks arriving afterwards do not revive the abandoned start.
            player.emitTracks(audio = emptyList(), subs = listOf(Track("sub-en", "English", "en")))
            runCurrent()
            assertFalse(controller.active.value)
            assertTrue(player.extractionCalls.isEmpty())
        }

    private companion object {
        val OTHER_SRT =
            """
            1
            00:00:01,000 --> 00:00:04,000
            From the container.

            """.trimIndent()

        val ASS =
            """
            [Script Info]
            ScriptType: v4.00+

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,From ASS.

            """.trimIndent()

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
