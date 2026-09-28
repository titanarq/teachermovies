package com.teachermovies.bridge.explain

import com.teachermovies.bridge.protocol.ExplanationDto
import com.teachermovies.bridge.protocol.ExplanationPointDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reply schema of #291: extraction, limits and what is tolerated. */
class ExplainReplyTest {
    private fun valid(
        text: String,
        hasSpanishLine: Boolean = true,
    ): ExplanationDto {
        val result = ExplainReply.parse(text, hasSpanishLine)
        assertTrue("expected valid, got $result", result is ExplainReply.Result.Valid)
        return (result as ExplainReply.Result.Valid).explanation
    }

    private fun problem(text: String): String {
        val result = ExplainReply.parse(text, hasSpanishLine = true)
        assertTrue("expected invalid, got $result", result is ExplainReply.Result.Invalid)
        return (result as ExplainReply.Result.Invalid).problem
    }

    private fun points(n: Int): String =
        (1..n).joinToString(",", "[", "]") { """{"expresion":"e$it","explicacion":"x$it"}""" }

    @Test
    fun `a full reply becomes the explanation, tagged with the prompt version`() {
        val explanation =
            valid(
                """{"resumen":" Le desea suerte. ",""" +
                    """"puntos":[{"expresion":"break a leg","explicacion":"«Mucha suerte»."}],""" +
                    """"diferencia_subtitulo":"El subtítulo adapta el sentido.","extra":1}""",
            )
        assertEquals(
            ExplanationDto(
                promptVersion = ExplainPrompt.VERSION,
                resumen = "Le desea suerte.",
                puntos = listOf(ExplanationPointDto("break a leg", "«Mucha suerte».")),
                diferenciaSubtitulo = "El subtítulo adapta el sentido.",
            ),
            explanation,
        )
    }

    @Test
    fun `a fenced reply with no points and no difference is valid`() {
        val explanation = valid("```json\n{\"resumen\":\"Hola.\",\"puntos\":[],\"diferencia_subtitulo\":null}\n```")
        assertEquals(emptyList<ExplanationPointDto>(), explanation.puntos)
        assertEquals(null, explanation.diferenciaSubtitulo)
    }

    @Test
    fun `missing points and a missing or blank difference are empty and null`() {
        assertEquals(emptyList<ExplanationPointDto>(), valid("""{"resumen":"Hola."}""").puntos)
        assertEquals(null, valid("""{"resumen":"Hola.","puntos":[],"diferencia_subtitulo":"  "}""").diferenciaSubtitulo)
    }

    @Test
    fun `a difference is dropped when the job carried no spanish line`() {
        val explanation =
            valid("""{"resumen":"Hola.","puntos":[],"diferencia_subtitulo":"Otra cosa."}""", hasSpanishLine = false)
        assertEquals(null, explanation.diferenciaSubtitulo)
    }

    @Test
    fun `the limits hold exactly at their edge`() {
        val summary = "a".repeat(ExplanationDto.MAX_SUMMARY_CHARS)
        val point = "ñ".repeat(ExplanationDto.MAX_POINT_CHARS)
        val explanation =
            valid(
                """{"resumen":"$summary","puntos":[{"expresion":"e","explicacion":"$point"},{"expresion":"f","explicacion":"g"},{"expresion":"h","explicacion":"i"}]}""",
            )
        assertEquals(3, explanation.puntos.size)
        assertTrue(problem("""{"resumen":"${summary}a","puntos":[]}""").contains("resumen"))
        assertTrue(
            problem(
                """{"resumen":"a","puntos":[{"expresion":"e","explicacion":"${point}ñ"}]}""",
            ).contains("explicacion del punto 1"),
        )
    }

    @Test
    fun `lengths count code points, not UTF-16 units`() {
        val emoji = "😀".repeat(ExplanationDto.MAX_SUMMARY_CHARS)
        assertEquals(
            ExplanationDto.MAX_SUMMARY_CHARS,
            valid("""{"resumen":"$emoji"}""").resumen.codePointCount(0, emoji.length),
        )
    }

    @Test
    fun `each schema break is named without quoting the reply`() {
        assertEquals("no contiene ningún objeto JSON", problem("Lo siento, no sé."))
        assertTrue(problem("""{"puntos":[]}""").contains("falta \"resumen\""))
        assertTrue(problem("""{"resumen":""}""").contains("falta \"resumen\""))
        assertTrue(problem("""{"resumen":3}""").contains("\"resumen\" no es un texto"))
        assertTrue(problem("""{"resumen":"a","puntos":{}}""").contains("no es una lista"))
        assertTrue(problem("""{"resumen":"a","puntos":${points(4)}}""").contains("4 elementos"))
        assertTrue(problem("""{"resumen":"a","puntos":["e"]}""").contains("el punto 1 no es un objeto"))
        assertTrue(problem("""{"resumen":"a","puntos":[{"explicacion":"x"}]}""").contains("expresion del punto 1"))
        assertTrue(problem("""{"resumen":"a","puntos":[{"expresion":"e"}]}""").contains("explicacion del punto 1"))
        val longExpression = "e".repeat(ExplanationDto.MAX_EXPRESSION_CHARS + 1)
        assertTrue(
            problem(
                """{"resumen":"a","puntos":[{"expresion":"$longExpression","explicacion":"x"}]}""",
            ).contains("expresion"),
        )
        val longDifference = "d".repeat(ExplanationDto.MAX_DIFFERENCE_CHARS + 1)
        assertTrue(
            problem("""{"resumen":"a","diferencia_subtitulo":"$longDifference"}""").contains("diferencia_subtitulo"),
        )
        assertTrue(problem("""{"resumen":"a","diferencia_subtitulo":false}""").contains("no es un texto"))
    }
}
