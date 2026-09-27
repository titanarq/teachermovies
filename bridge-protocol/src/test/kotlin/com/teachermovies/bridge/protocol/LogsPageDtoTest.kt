package com.teachermovies.bridge.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class LogsPageDtoTest {
    private val page =
        LogsPageDto(
            bootId = "boot-1",
            entries =
                listOf(
                    LogEntryDto(
                        seq = 1L,
                        timeMs = 1758900000000L,
                        level = "warn",
                        module = "torrent",
                        message = "stalled",
                    ),
                ),
        )

    private val golden =
        """{"bootId":"boot-1","entries":[""" +
            """{"seq":1,"timeMs":1758900000000,"level":"warn","module":"torrent","message":"stalled"}]}"""

    @Test
    fun `encodes to the golden json`() {
        assertEquals(golden, Json.encodeToString(LogsPageDto.serializer(), page))
    }

    @Test
    fun `decodes from the golden json`() {
        assertEquals(page, Json.decodeFromString(LogsPageDto.serializer(), golden))
    }
}
