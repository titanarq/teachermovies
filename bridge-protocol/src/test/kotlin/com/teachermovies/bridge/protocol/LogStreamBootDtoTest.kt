package com.teachermovies.bridge.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class LogStreamBootDtoTest {
    private val boot = LogStreamBootDto(bootId = "boot-1")

    private val golden = """{"bootId":"boot-1"}"""

    @Test
    fun `encodes to the golden json`() {
        assertEquals(golden, Json.encodeToString(LogStreamBootDto.serializer(), boot))
    }

    @Test
    fun `decodes from the golden json`() {
        assertEquals(boot, Json.decodeFromString(LogStreamBootDto.serializer(), golden))
    }
}
