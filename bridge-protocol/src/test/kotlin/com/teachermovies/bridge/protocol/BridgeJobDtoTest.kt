package com.teachermovies.bridge.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeJobDtoTest {
    private val translate: BridgeJobDto = TranslateJobDto(id = "j1", line = "Break a leg!")
    private val translateGolden = """{"kind":"translate","id":"j1","line":"Break a leg!"}"""

    private val explain: BridgeJobDto =
        ExplainJobDto(
            id = "j2",
            title = "Heat",
            line = "Break a leg!",
            before = listOf("a", "b", "c"),
            after = listOf("d", "e"),
            spanishLine = null,
        )
    private val explainGolden =
        """{"kind":"explain","id":"j2","title":"Heat","line":"Break a leg!",""" +
            """"before":["a","b","c"],"after":["d","e"],"spanishLine":null}"""

    @Test
    fun `a translate job encodes to the golden json with its kind`() {
        assertEquals(translateGolden, Json.encodeToString(BridgeJobDto.serializer(), translate))
        assertEquals(translate, Json.decodeFromString(BridgeJobDto.serializer(), translateGolden))
    }

    @Test
    fun `an explain job encodes to the golden json with its kind`() {
        assertEquals(explainGolden, Json.encodeToString(BridgeJobDto.serializer(), explain))
        assertEquals(explain, Json.decodeFromString(BridgeJobDto.serializer(), explainGolden))
    }

    @Test
    fun `a cancel encodes to the golden json`() {
        val golden = """{"id":"j1"}"""
        assertEquals(golden, Json.encodeToString(BridgeCancelDto.serializer(), BridgeCancelDto("j1")))
        assertEquals(BridgeCancelDto("j1"), Json.decodeFromString(BridgeCancelDto.serializer(), golden))
    }

    @Test
    fun `results decode by status`() {
        assertEquals(
            BridgeJobResultDto.Done("¡Mucha suerte!"),
            Json.decodeFromString(BridgeJobResultDto.serializer(), """{"status":"ok","text":"¡Mucha suerte!"}"""),
        )
        assertEquals(
            BridgeJobResultDto.Failed("rate_limited"),
            Json.decodeFromString(BridgeJobResultDto.serializer(), """{"status":"error","code":"rate_limited"}"""),
        )
    }

    @Test
    fun `results encode to the golden json`() {
        assertEquals(
            """{"status":"ok","text":"hola"}""",
            Json.encodeToString(BridgeJobResultDto.serializer(), BridgeJobResultDto.Done("hola")),
        )
        assertEquals(
            """{"status":"error","code":"daily_cap","message":"300 reached"}""",
            Json.encodeToString(BridgeJobResultDto.serializer(), BridgeJobResultDto.Failed("daily_cap", "300 reached")),
        )
    }
}
