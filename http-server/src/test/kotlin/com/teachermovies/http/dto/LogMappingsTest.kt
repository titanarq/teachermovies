package com.teachermovies.http.dto

import com.teachermovies.bridge.protocol.LogEntryDto
import com.teachermovies.core.log.LogEntry
import com.teachermovies.core.log.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class LogMappingsTest {
    @Test
    fun `copies every field and lower-cases the level`() {
        val entry =
            LogEntry(
                seq = 7L,
                timeMs = 1_700_000_000_042L,
                level = LogLevel.WARN,
                module = "torrent",
                message = "stalled",
            )

        assertEquals(
            LogEntryDto(
                seq = 7L,
                timeMs = 1_700_000_000_042L,
                level = "warn",
                module = "torrent",
                message = "stalled",
            ),
            entry.toDto(),
        )
    }

    @Test
    fun `every level has its lower-case wire value`() {
        val base = LogEntry(seq = 1L, timeMs = 0L, level = LogLevel.INFO, module = "m", message = "x")
        val wireValues = LogLevel.entries.map { level -> base.copy(level = level).toDto().level }

        assertEquals(listOf("debug", "info", "warn", "error"), wireValues)
    }
}
