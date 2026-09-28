package com.teachermovies.assistant.explanation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExplanationTest {
    @Test
    fun `parses the explain handler document`() {
        val explanation =
            Explanation.parse(
                """
                {"resumen": " Le hará una oferta irrechazable. ",
                 "puntos": [{"expresion": "gonna", "explicacion": "forma coloquial de going to"}],
                 "diferencia_subtitulo": "El subtítulo lo suaviza.",
                 "promptVersion": "explain-v2"}
                """.trimIndent(),
            )
        assertEquals(
            Explanation(
                summary = "Le hará una oferta irrechazable.",
                points = listOf(ExplanationPoint("gonna", "forma coloquial de going to")),
                subtitleNote = "El subtítulo lo suaviza.",
                promptVersion = "explain-v2",
            ),
            explanation,
        )
    }

    @Test
    fun `note and prompt version are optional, snake case tag accepted`() {
        assertEquals(
            Explanation("Resumen.", emptyList(), null, null),
            Explanation.parse("""{"resumen": "Resumen.", "puntos": [], "diferencia_subtitulo": null}"""),
        )
        val tagged = Explanation.parse("""{"resumen": "R", "diferencia_subtitulo": "  ", "prompt_version": "p7"}""")
        assertEquals("p7", tagged?.promptVersion)
        assertNull(Explanation.parse("""{"resumen": "R", "diferencia_subtitulo": "  "}""")?.subtitleNote)
    }

    @Test
    fun `malformed points are dropped and at most three kept`() {
        val explanation =
            Explanation.parse(
                """
                {"resumen": "R", "puntos": [
                  {"expresion": "a", "explicacion": "1"},
                  {"expresion": "b"},
                  "not an object",
                  {"expresion": "c", "explicacion": 3},
                  {"expresion": "d", "explicacion": "4"},
                  {"expresion": "e", "explicacion": "5"},
                  {"expresion": "f", "explicacion": "6"}
                ]}
                """.trimIndent(),
            )
        assertEquals(listOf("a", "d", "e"), explanation?.points?.map { it.expression })
    }

    @Test
    fun `no usable summary is no explanation`() {
        assertNull(Explanation.parse("not json"))
        assertNull(Explanation.parse("[]"))
        assertNull(Explanation.parse("\"text\""))
        assertNull(Explanation.parse("{}"))
        assertNull(Explanation.parse("""{"resumen": "   "}"""))
        assertNull(Explanation.parse("""{"resumen": 42}"""))
        assertNull(Explanation.parse(""))
    }
}
