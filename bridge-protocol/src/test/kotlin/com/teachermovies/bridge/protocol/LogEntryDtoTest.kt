package com.teachermovies.bridge.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** The golden strings ARE the wire contract (ADR-0005 §1): every key, its spelling and order. */
class LogEntryDtoTest {
    private val entry =
        LogEntryDto(
            seq = 42L,
            timeMs = 1758900000000L,
            level = "info",
            module = "torrent",
            message = "downloaded 42%",
        )

    private val golden =
        """{"seq":42,"timeMs":1758900000000,"level":"info","module":"torrent","message":"downloaded 42%"}"""

    @Test
    fun `encodes to the golden json`() {
        assertEquals(golden, Json.encodeToString(LogEntryDto.serializer(), entry))
    }

    @Test
    fun `decodes from the golden json`() {
        assertEquals(entry, Json.decodeFromString(LogEntryDto.serializer(), golden))
    }
}
