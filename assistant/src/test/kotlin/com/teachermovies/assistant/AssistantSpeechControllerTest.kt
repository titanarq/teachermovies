package com.teachermovies.assistant

import com.teachermovies.assistant.speech.SpeakerAvailability
import com.teachermovies.assistant.speech.SpeechLanguage
import com.teachermovies.assistant.speech.fake.FakeSpeaker
import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.assistant.translation.TranslationProvider
import com.teachermovies.assistant.translation.TranslationResult
import com.teachermovies.assistant.translation.fake.FakeTranslationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantSpeechControllerTest {
    private val hello =
        CapturedLine(SubtitleCue(index = 0, startMs = 1_000L, endMs = 2_000L, text = "Hello."), capturedAtMs = 1_500L)
    private val bye =
        CapturedLine(SubtitleCue(index = 1, startMs = 5_000L, endMs = 6_000L, text = "Bye."), capturedAtMs = 5_500L)

    private class Fixture(
        val speaker: FakeSpeaker,
        val translations: FakeTranslationProvider,
        val controllerJob: Job,
        val controller: AssistantSpeechController,
    ) {
        val activeCoroutines: Int
            get() = controllerJob.children.count { it.isActive }
    }

    /** Runs everything due within the next minute of virtual time, including `backgroundScope` work. */
    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    private fun TestScope.fixture(): Fixture {
        val speaker = FakeSpeaker()
        val translations =
            FakeTranslationProvider().apply {
                translations["Hello."] = "Hola."
                translations["Bye."] = "Adiós."
            }
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

            assertEquals(setOf(SpeechLanguage.EN, SpeechLanguage.ES), f.controller.state.value.speechAvailable)
            assertEquals(SpeakerAvailability.Ready, f.speaker.availability.value)
        }

    @Test
    fun `prepare with MissingVoice removes only the missing languages`() =
        runTest {
            val f = fixture()
            f.speaker.nextAvailability = SpeakerAvailability.MissingVoice(setOf(SpeechLanguage.ES))

            f.controller.prepare()

            assertEquals(setOf(SpeechLanguage.EN), f.controller.state.value.speechAvailable)
        }

    @Test
    fun `prepare with every voice missing makes speech unavailable`() =
        runTest {
            val f = fixture()
            f.speaker.nextAvailability = SpeakerAvailability.MissingVoice(setOf(SpeechLanguage.EN, SpeechLanguage.ES))

            f.controller.prepare()

            assertEquals(emptySet<SpeechLanguage>(), f.controller.state.value.speechAvailable)
        }

    @Test
    fun `prepare with EngineUnavailable makes speech unavailable`() =
        runTest {
            val f = fixture()
            f.speaker.nextAvailability = SpeakerAvailability.EngineUnavailable

            f.controller.prepare()

            assertEquals(emptySet<SpeechLanguage>(), f.controller.state.value.speechAvailable)
        }

    @Test
    fun `a second prepare does not re-initialise the engine`() =
        runTest {
            val f = fixture()
            f.controller.prepare()
            f.speaker.nextAvailability = SpeakerAvailability.EngineUnavailable

            f.controller.prepare()

            assertEquals(setOf(SpeechLanguage.EN, SpeechLanguage.ES), f.controller.state.value.speechAvailable)
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
            f.speaker.nextAvailability = SpeakerAvailability.MissingVoice(setOf(SpeechLanguage.EN))
            f.controller.prepare()

            f.controller.reset()

            assertEquals(AssistantSpeechState(speechAvailable = setOf(SpeechLanguage.ES)), f.controller.state.value)
        }

    @Test
    fun `translate goes Loading then Ready`() =
        runTest {
            val f = fixture()
            f.translations.delayMs = 100

            f.controller.translate(hello)
            assertEquals(TranslationUiState.Loading, f.controller.state.value.translation)
            runCurrent()
            assertEquals(TranslationUiState.Loading, f.controller.state.value.translation)

            settle()
            assertEquals(TranslationUiState.Ready("Hola."), f.controller.state.value.translation)
            assertEquals(listOf("Hello."), f.translations.requests)
            assertEquals(0, f.activeCoroutines)
        }

    @Test
    fun `Offline maps to Failed OFFLINE`() =
        runTest {
            val f = fixture()
            f.translations.nextResult = TranslationResult.Offline

            f.controller.translate(hello)
            settle()

            assertEquals(
                TranslationUiState.Failed(TranslationFailure.OFFLINE),
                f.controller.state.value.translation,
            )
        }

    @Test
    fun `Unavailable maps to Failed UNAVAILABLE`() =
        runTest {
            val f = fixture()
            f.translations.nextResult = TranslationResult.Unavailable("refused")

            f.controller.translate(hello)
            settle()

            assertEquals(
                TranslationUiState.Failed(TranslationFailure.UNAVAILABLE),
                f.controller.state.value.translation,
            )
        }

    @Test
    fun `a throwing provider becomes Failed UNAVAILABLE`() =
        runTest {
            val throwing =
                object : TranslationProvider {
                    override val id = "throwing"

                    override suspend fun translate(
                        text: String,
                        from: SpeechLanguage,
                        to: SpeechLanguage,
                    ): TranslationResult = throw IllegalStateException("boom")
                }
            val controller = AssistantSpeechController(FakeSpeaker(), throwing, backgroundScope)

            controller.translate(hello)
            settle()

            assertEquals(
                TranslationUiState.Failed(TranslationFailure.UNAVAILABLE),
                controller.state.value.translation,
            )
        }

    @Test
    fun `translating the same line again does not call the provider again`() =
        runTest {
            val f = fixture()
            f.translations.delayMs = 100
            f.controller.translate(hello)
            f.controller.translate(hello) // while Loading
            settle()

            f.controller.translate(hello) // while Ready
            settle()

            assertEquals(listOf("Hello."), f.translations.requests)
            assertEquals(TranslationUiState.Ready("Hola."), f.controller.state.value.translation)
        }

    @Test
    fun `translating a different line cancels the in-flight call and starts over`() =
        runTest {
            val f = fixture()
            f.translations.delayMs = 100
            f.controller.translate(hello)
            advanceTimeBy(50)

            f.controller.translate(bye)
            assertEquals(TranslationUiState.Loading, f.controller.state.value.translation)
            runCurrent()
            assertEquals(1, f.activeCoroutines)

            settle()
            assertEquals(listOf("Hello.", "Bye."), f.translations.requests)
            assertEquals(TranslationUiState.Ready("Adiós."), f.controller.state.value.translation)
        }

    @Test
    fun `a different line clears the previous Ready result`() =
        runTest {
            val f = fixture()
            f.controller.translate(hello)
            settle()
            f.translations.delayMs = 100

            f.controller.translate(bye)

            assertEquals(TranslationUiState.Loading, f.controller.state.value.translation)
            settle()
            assertEquals(TranslationUiState.Ready("Adiós."), f.controller.state.value.translation)
        }

    @Test
    fun `reset cancels an in-flight translation`() =
        runTest {
            val f = fixture()
            f.translations.delayMs = 100
            f.controller.translate(hello)
            runCurrent()
            assertEquals(1, f.activeCoroutines)

            f.controller.reset()
            settle()

            assertEquals(AssistantSpeechState(), f.controller.state.value)
            assertEquals(0, f.activeCoroutines)
        }

    @Test
    fun `speakTranslation does nothing before Ready and speaks Spanish after`() =
        runTest {
            val f = fixture()
            f.controller.prepare()
            f.translations.delayMs = 100

            assertFalse(f.controller.speakTranslation()) // Idle
            f.controller.translate(hello)
            assertFalse(f.controller.speakTranslation()) // Loading
            assertTrue(f.speaker.spoken.isEmpty())

            settle()
            assertTrue(f.controller.speakTranslation())
            assertEquals(listOf("Hola." to SpeechLanguage.ES), f.speaker.spoken)
            assertTrue(f.controller.state.value.speaking)
        }

    @Test
    fun `speakTranslation does nothing after a failure`() =
        runTest {
            val f = fixture()
            f.controller.prepare()
            f.translations.nextResult = TranslationResult.Offline
            f.controller.translate(hello)
            settle()

            assertFalse(f.controller.speakTranslation())
            assertTrue(f.speaker.spoken.isEmpty())
        }

    @Test
    fun `translateAndSpeak speaks the translation as soon as it arrives`() =
        runTest {
            val f = fixture()
            f.controller.prepare()
            f.translations.delayMs = 100

            f.controller.translateAndSpeak(hello)
            runCurrent()
            assertTrue(f.speaker.spoken.isEmpty())

            settle()
            assertEquals(TranslationUiState.Ready("Hola."), f.controller.state.value.translation)
            assertEquals(listOf("Hola." to SpeechLanguage.ES), f.speaker.spoken)
            assertTrue(f.controller.state.value.speaking)
        }

    @Test
    fun `translateAndSpeak reuses a Ready result for the same line`() =
        runTest {
            val f = fixture()
            f.controller.prepare()
            f.controller.translate(hello)
            settle()

            f.controller.translateAndSpeak(hello)
            settle()

            assertEquals(listOf("Hello."), f.translations.requests)
            assertEquals(listOf("Hola." to SpeechLanguage.ES), f.speaker.spoken)
        }

    @Test
    fun `translateAndSpeak while the same line is Loading speaks once it arrives without a second call`() =
        runTest {
            val f = fixture()
            f.controller.prepare()
            f.translations.delayMs = 100
            f.controller.translate(hello)

            f.controller.translateAndSpeak(hello)
            settle()

            assertEquals(listOf("Hello."), f.translations.requests)
            assertEquals(listOf("Hola." to SpeechLanguage.ES), f.speaker.spoken)
        }

    @Test
    fun `translateAndSpeak on failure speaks nothing and keeps the failure`() =
        runTest {
            val f = fixture()
            f.controller.prepare()
            f.translations.nextResult = TranslationResult.Unavailable("refused")

            f.controller.translateAndSpeak(hello)
            settle()

            assertTrue(f.speaker.spoken.isEmpty())
            assertEquals(
                TranslationUiState.Failed(TranslationFailure.UNAVAILABLE),
                f.controller.state.value.translation,
            )
            assertFalse(f.controller.state.value.speaking)
        }

    @Test
    fun `a line switched away from is never spoken`() =
        runTest {
            val f = fixture()
            f.controller.prepare()
            f.translations.delayMs = 100
            f.controller.translateAndSpeak(hello)
            advanceTimeBy(50)

            f.controller.translate(bye)
            settle()

            assertTrue(f.speaker.spoken.isEmpty())
            assertEquals(TranslationUiState.Ready("Adiós."), f.controller.state.value.translation)
        }

    @Test
    fun `without speech every speaking call returns false but translation still works`() =
        runTest {
            val f = fixture()
            f.speaker.nextAvailability = SpeakerAvailability.EngineUnavailable
            f.controller.prepare()

            assertFalse(f.controller.speakOriginal(hello))
            f.controller.translateAndSpeak(hello)
            settle()
            assertFalse(f.controller.speakTranslation())

            assertTrue(f.speaker.spoken.isEmpty())
            assertEquals(
                AssistantSpeechState(translation = TranslationUiState.Ready("Hola."), speechAvailable = emptySet()),
                f.controller.state.value,
            )
            assertEquals(0, f.activeCoroutines)
        }

    @Test
    fun `with the Spanish voice missing English is spoken, Spanish refused, translation Ready`() =
        runTest {
            val f = fixture()
            f.speaker.nextAvailability = SpeakerAvailability.MissingVoice(setOf(SpeechLanguage.ES))
            f.controller.prepare()

            assertTrue(f.controller.speakOriginal(hello))
            f.speaker.finishCurrentUtterance()
            f.controller.translateAndSpeak(hello)
            settle()
            assertFalse(f.controller.speakTranslation())

            assertEquals(listOf("Hello." to SpeechLanguage.EN), f.speaker.spoken)
            assertEquals(TranslationUiState.Ready("Hola."), f.controller.state.value.translation)
            assertFalse(f.controller.state.value.speaking)
        }

    @Test
    fun `with the English voice missing Spanish is spoken, English refused`() =
        runTest {
            val f = fixture()
            f.speaker.nextAvailability = SpeakerAvailability.MissingVoice(setOf(SpeechLanguage.EN))
            f.controller.prepare()

            assertFalse(f.controller.speakOriginal(hello))
            assertTrue(f.speaker.spoken.isEmpty())
            f.controller.translateAndSpeak(hello)
            settle()

            assertEquals(listOf("Hola." to SpeechLanguage.ES), f.speaker.spoken)
            assertEquals(TranslationUiState.Ready("Hola."), f.controller.state.value.translation)
            assertTrue(f.controller.speakTranslation())
        }

    @Test
    fun `with every voice missing nothing is spoken but translation still works`() =
        runTest {
            val f = fixture()
            f.speaker.nextAvailability = SpeakerAvailability.MissingVoice(setOf(SpeechLanguage.EN, SpeechLanguage.ES))
            f.controller.prepare()

            assertFalse(f.controller.speakOriginal(hello))
            f.controller.translateAndSpeak(hello)
            settle()
            assertFalse(f.controller.speakTranslation())

            assertTrue(f.speaker.spoken.isEmpty())
            assertEquals(
                AssistantSpeechState(translation = TranslationUiState.Ready("Hola."), speechAvailable = emptySet()),
                f.controller.state.value,
            )
            assertEquals(0, f.activeCoroutines)
        }

    @Test
    fun `reset clears everything after speaking a translation`() =
        runTest {
            val f = fixture()
            f.controller.prepare()
            f.controller.translateAndSpeak(hello)
            settle()
            assertTrue(f.controller.state.value.speaking)

            f.controller.reset()
            settle()

            assertEquals(AssistantSpeechState(), f.controller.state.value)
            assertEquals(0, f.activeCoroutines)
            assertFalse(f.controller.speakTranslation())
        }
}
