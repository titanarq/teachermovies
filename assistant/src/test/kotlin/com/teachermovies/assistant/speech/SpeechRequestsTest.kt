package com.teachermovies.assistant.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRequestsTest {
    @Test
    fun `utterance id is stable for the same sequence number`() {
        assertEquals(SpeechRequests.utteranceId(7), SpeechRequests.utteranceId(7))
    }

    @Test
    fun `utterance ids are unique across sequence numbers`() {
        val ids = (0L..999L).map(SpeechRequests::utteranceId) + SpeechRequests.utteranceId(Long.MAX_VALUE)
        assertEquals(ids.size, ids.toSet().size)
        assertNotEquals(SpeechRequests.utteranceId(1), SpeechRequests.utteranceId(11))
    }

    @Test
    fun `blank text is not speakable`() {
        assertFalse(SpeechRequests.isSpeakable(""))
        assertFalse(SpeechRequests.isSpeakable("   "))
        assertFalse(SpeechRequests.isSpeakable("\n\t "))
    }

    @Test
    fun `punctuation and subtitle artefacts are not speakable`() {
        listOf("-", "...", "…", "♪", "♪ ♪", "- ...", "?!", "♫", "-- ♪ --", "\"\"").forEach {
            assertFalse("'$it' should not be speakable", SpeechRequests.isSpeakable(it))
        }
    }

    @Test
    fun `text with words or numbers is speakable`() {
        listOf("Hello", "- What?", "♪ Happy birthday ♪", "...and then", "42", "¿Qué?", "Ça va").forEach {
            assertTrue("'$it' should be speakable", SpeechRequests.isSpeakable(it))
        }
    }
}
