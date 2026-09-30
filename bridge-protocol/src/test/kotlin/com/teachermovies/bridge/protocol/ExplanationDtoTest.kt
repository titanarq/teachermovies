package com.teachermovies.bridge.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ExplanationDtoTest {
    private val explanation =
        ExplanationDto(
            promptVersion = "explain-v2",
            resumen = "Le desea suerte.",
            puntos = listOf(ExplanationPointDto(expresion = "break a leg", explicacion = "«Mucha mierda».")),
            diferenciaSubtitulo = null,
        )
    private val golden =
        """{"promptVersion":"explain-v2","resumen":"Le desea suerte.",""" +
            """"puntos":[{"expresion":"break a leg","explicacion":"«Mucha mierda»."}],"diferencia_subtitulo":null}"""

    @Test
    fun `an explanation encodes to the golden json, null difference included`() {
        assertEquals(golden, Json.encodeToString(ExplanationDto.serializer(), explanation))
        assertEquals(explanation, Json.decodeFromString(ExplanationDto.serializer(), golden))
    }
}
