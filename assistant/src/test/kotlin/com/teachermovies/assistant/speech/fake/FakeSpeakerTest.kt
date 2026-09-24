package com.teachermovies.assistant.speech.fake

import com.teachermovies.assistant.speech.SpeakerAvailability
import com.teachermovies.assistant.speech.SpeakerState
import com.teachermovies.assistant.speech.SpeechLanguage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSpeakerTest {
    private val speaker = FakeSpeaker()

    @Test
    fun `availability is null until prepare runs`() =
        runTest {
            assertNull(speaker.availability.value)
            assertEquals(SpeakerAvailability.Ready, speaker.prepare())
            assertEquals(SpeakerAvailability.Ready, speaker.availability.value)
        }

    @Test
    fun `speak before prepare is refused and stays idle`() {
        assertFalse(speaker.speak("Hello there", SpeechLanguage.EN))
        assertEquals(SpeakerState.Idle, speaker.state.value)
        assertTrue(speaker.spoken.isEmpty())
    }

    @Test
    fun `unavailable engine refuses every language`() =
        runTest {
            speaker.nextAvailability = SpeakerAvailability.EngineUnavailable
            assertEquals(SpeakerAvailability.EngineUnavailable, speaker.prepare())
            assertEquals(SpeakerAvailability.EngineUnavailable, speaker.availability.value)

            assertFalse(speaker.speak("Hello", SpeechLanguage.EN))
            assertFalse(speaker.speak("Hola", SpeechLanguage.ES))
            assertEquals(SpeakerState.Idle, speaker.state.value)
            assertTrue(speaker.spoken.isEmpty())
        }

    @Test
    fun `missing voice refuses the reported language`() =
        runTest {
            speaker.nextAvailability = SpeakerAvailability.MissingVoice(SpeechLanguage.ES)
            assertEquals(SpeakerAvailability.MissingVoice(SpeechLanguage.ES), speaker.prepare())

            assertFalse(speaker.speak("Hola", SpeechLanguage.ES))
            assertEquals(SpeakerState.Idle, speaker.state.value)
            assertTrue(speaker.spoken.isEmpty())
        }

    @Test
    fun `blank text is refused`() =
        runTest {
            speaker.prepare()
            assertFalse(speaker.speak("", SpeechLanguage.EN))
            assertFalse(speaker.speak("   \n\t", SpeechLanguage.EN))
            assertEquals(SpeakerState.Idle, speaker.state.value)
            assertTrue(speaker.spoken.isEmpty())
        }

    @Test
    fun `accepted speak is recorded and goes speaking then idle on finish`() =
        runTest {
            speaker.prepare()
            assertTrue(speaker.speak("Hello there", SpeechLanguage.EN))
            assertEquals(SpeakerState.Speaking("Hello there", SpeechLanguage.EN), speaker.state.value)

            speaker.finishCurrentUtterance()
            assertEquals(SpeakerState.Idle, speaker.state.value)

            assertTrue(speaker.speak("Hola", SpeechLanguage.ES))
            assertEquals(SpeakerState.Speaking("Hola", SpeechLanguage.ES), speaker.state.value)
            speaker.failCurrentUtterance()
            assertEquals(SpeakerState.Idle, speaker.state.value)

            assertEquals(
                listOf("Hello there" to SpeechLanguage.EN, "Hola" to SpeechLanguage.ES),
                speaker.spoken,
            )
        }

    @Test
    fun `a new speak replaces the current utterance`() =
        runTest {
            speaker.prepare()
            speaker.speak("First", SpeechLanguage.EN)
            speaker.speak("Second", SpeechLanguage.EN)
            assertEquals(SpeakerState.Speaking("Second", SpeechLanguage.EN), speaker.state.value)
        }

    @Test
    fun `stop goes back to idle`() =
        runTest {
            speaker.prepare()
            speaker.speak("Hello", SpeechLanguage.EN)
            speaker.stop()
            assertEquals(SpeakerState.Idle, speaker.state.value)
        }

    @Test
    fun `shutdown is idempotent and safe before prepare`() =
        runTest {
            speaker.shutdown()
            speaker.shutdown()
            assertEquals(1, speaker.shutdownCount)
            assertEquals(SpeakerState.Idle, speaker.state.value)
        }

    @Test
    fun `shutdown stops speech and refuses later speak`() =
        runTest {
            speaker.prepare()
            speaker.speak("Hello", SpeechLanguage.EN)
            speaker.shutdown()
            assertEquals(SpeakerState.Idle, speaker.state.value)
            assertFalse(speaker.speak("Again", SpeechLanguage.EN))
            speaker.shutdown()
            assertEquals(1, speaker.shutdownCount)
            assertEquals(listOf("Hello" to SpeechLanguage.EN), speaker.spoken)
        }
}
