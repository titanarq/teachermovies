package com.teachermovies.assistant

import com.teachermovies.assistant.speech.SpeakerAvailability
import com.teachermovies.assistant.speech.SpeechLanguage
import com.teachermovies.assistant.speech.fake.FakeSpeaker
import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.assistant.translation.fake.FakeTranslationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantSpeechControllerTest {
    private val hello =
        CapturedLine(SubtitleCue(index = 0, startMs = 1_000L, endMs = 2_000L, text = "Hello."), capturedAtMs = 1_500L)

    private class Fixture(
        val speaker: FakeSpeaker,
        val translations: FakeTranslationProvider,
        val controllerJob: Job,
        val controller: AssistantSpeechController,
    ) {
        val activeCoroutines: Int
            get() = controllerJob.children.count { it.isActive }
    }

    private fun TestScope.fixture(): Fixture {
        val speaker = FakeSpeaker()
        val translations = FakeTranslationProvider()
        // A child scope of its own, so the test can count the controller's coroutines.
        val controllerJob = Job(backgroundScope.coroutineContext.job)
        val controller =
            AssistantSpeechController(
                speaker,
                translations,
                CoroutineScope(backgroundScope.coroutineContext + controllerJob),
            )
        return Fixture(speaker, translations, controllerJob, controller)
    }

    @Test
    fun `prepare with Ready keeps speech available`() =
        runTest {
            val f = fixture()

            f.controller.prepare()

            assertTrue(f.controller.state.value.speechAvailable)
            assertEquals(SpeakerAvailability.Ready, f.speaker.availability.value)
        }

    @Test
    fun `prepare with MissingVoice makes speech unavailable`() =
        runTest {
            val f = fixture()
            f.speaker.nextAvailability = SpeakerAvailability.MissingVoice(setOf(SpeechLanguage.ES))

            f.controller.prepare()

            assertFalse(f.controller.state.value.speechAvailable)
        }

    @Test
    fun `prepare with EngineUnavailable makes speech unavailable`() =
        runTest {
            val f = fixture()
            f.speaker.nextAvailability = SpeakerAvailability.EngineUnavailable

            f.controller.prepare()

            assertFalse(f.controller.state.value.speechAvailable)
        }

    @Test
    fun `a second prepare does not re-initialise the engine`() =
        runTest {
            val f = fixture()
            f.controller.prepare()
            f.speaker.nextAvailability = SpeakerAvailability.EngineUnavailable

            f.controller.prepare()

            assertTrue(f.controller.state.value.speechAvailable)
            assertEquals(SpeakerAvailability.Ready, f.speaker.availability.value)
        }

    @Test
    fun `speakOriginal says the cue text in English`() =
        runTest {
            val f = fixture()
            f.controller.prepare()

            assertTrue(f.controller.speakOriginal(hello))

            assertEquals(listOf("Hello." to SpeechLanguage.EN), f.speaker.spoken)
            assertTrue(f.controller.state.value.speaking)
        }

    @Test
    fun `speakOriginal returns false when the speaker refuses`() =
        runTest {
            val f = fixture()
            // Not prepared: the speaker refuses.

            assertFalse(f.controller.speakOriginal(hello))

            assertFalse(f.controller.state.value.speaking)
            assertEquals(0, f.activeCoroutines)
        }

    @Test
    fun `speaking follows the fake speaker`() =
        runTest {
            val f = fixture()
            f.controller.prepare()
            f.controller.speakOriginal(hello)
            runCurrent()
            assertTrue(f.controller.state.value.speaking)

            f.speaker.finishCurrentUtterance()
            runCurrent()
            assertFalse(f.controller.state.value.speaking)

            f.controller.speakOriginal(hello)
            runCurrent()
            assertTrue(f.controller.state.value.speaking)

            f.speaker.failCurrentUtterance()
            runCurrent()
            assertFalse(f.controller.state.value.speaking)
        }

    @Test
    fun `reset stops the speaker and cancels the speaking mirror`() =
        runTest {
            val f = fixture()
            f.controller.prepare()
            f.controller.speakOriginal(hello)
            runCurrent()
            assertEquals(1, f.activeCoroutines)

            f.controller.reset()
            runCurrent()

            assertEquals(AssistantSpeechState(), f.controller.state.value)
            assertEquals(0, f.activeCoroutines)
        }

    @Test
    fun `reset keeps speechAvailable`() =
        runTest {
            val f = fixture()
            f.speaker.nextAvailability = SpeakerAvailability.EngineUnavailable
            f.controller.prepare()

            f.controller.reset()

            assertEquals(AssistantSpeechState(speechAvailable = false), f.controller.state.value)
        }
}
