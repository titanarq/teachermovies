package com.teachermovies.bridge.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reply schema of #286: extraction, limits and what is tolerated. */
class TranslateReplyTest {
    private fun valid(text: String): String {
        val result = TranslateReply.parse(text)
        assertTrue("expected valid, got $result", result is TranslateReply.Result.Valid)
        return (result as TranslateReply.Result.Valid).translation
    }

    private fun problem(text: String): String {
        val result = TranslateReply.parse(text)
        assertTrue("expected invalid, got $result", result is TranslateReply.Result.Invalid)
        return (result as TranslateReply.Result.Invalid).problem
    }

    @Test
    fun `a fenced reply is the translation`() {
        assertEquals("¡Mucha suerte!", valid("```json\n{\"translation\":\"¡Mucha suerte!\"}\n```"))
    }

    @Test
    fun `prose around the object and unknown keys are ignored, and the translation is trimmed`() {
        assertEquals(
            "Hola.",
            valid("Aquí lo tienes: {\"nota\":\"literal\",\"translation\":\" Hola. \"} Que sepas que es informal."),
        )
    }

    @Test
    fun `the limit holds exactly at its edge`() {
        val longest = "ñ".repeat(TranslateReply.MAX_CHARS)
        assertEquals(longest, valid("""{"translation":"$longest"}"""))
        assertTrue(problem("""{"translation":"${longest}x"}""").contains("el máximo es ${TranslateReply.MAX_CHARS}"))
    }

    @Test
    fun `lengths count code points, not UTF-16 units`() {
        val emoji = "😀".repeat(TranslateReply.MAX_CHARS)
        assertEquals(
            TranslateReply.MAX_CHARS,
            valid("""{"translation":"$emoji"}""").codePointCount(0, emoji.length),
        )
    }

    @Test
    fun `each schema break is named without quoting the reply`() {
        assertEquals("no contiene ningún objeto JSON", problem("Lo siento, no sé."))
        assertEquals("no contiene ningún objeto JSON", problem("{\"translation\":\"sin cerrar"))
        assertTrue(problem("{}").contains("falta \"translation\""))
        assertTrue(problem("""{"traduccion":"Lo siento."}""").contains("falta \"translation\""))
        assertTrue(problem("""{"translation":null}""").contains("falta \"translation\""))
        assertTrue(problem("""{"translation":"   "}""").contains("está vacío"))
        assertTrue(problem("""{"translation":3}""").contains("\"translation\" no es un texto"))
        assertTrue(problem("""{"translation":true}""").contains("\"translation\" no es un texto"))
        assertTrue(problem("""{"translation":["Lo siento."]}""").contains("\"translation\" no es un texto"))
        val tooLong = problem("""{"translation":"${"a".repeat(TranslateReply.MAX_CHARS + 1)}"}""")
        assertTrue(tooLong, tooLong.contains("caracteres"))
        assertFalse(tooLong, tooLong.contains("aaaa"))
        val replies =
            listOf(
                "Lo siento, no sé.",
                """{"traduccion":"Lo siento."}""",
                """{"translation":["Lo siento."]}""",
            )
        for (reply in replies) {
            assertFalse(reply, problem(reply).contains("Lo siento"))
        }
    }
}
