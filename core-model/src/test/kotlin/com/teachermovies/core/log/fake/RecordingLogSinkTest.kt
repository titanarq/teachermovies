package com.teachermovies.core.log.fake

import com.teachermovies.core.log.LogEntry
import com.teachermovies.core.log.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingLogSinkTest {
    private val sink = RecordingLogSink()

    @Test
    fun startsEmpty() {
        assertEquals(emptyList<LogEntry>(), sink.entries)
        assertEquals(emptyList<String>(), sink.messages)
        assertNull(sink.lastEntry)
    }

    @Test
    fun keepsEveryLineItIsHandedOldestFirstAndUntouched() {
        val first = entry(1L, "starting the download")
        val second = entry(2L, "downloaded 42%", LogLevel.WARN)

        sink.record(first)
        sink.record(second)

        assertEquals(listOf(first, second), sink.entries)
        assertEquals(listOf("starting the download", "downloaded 42%"), sink.messages)
        assertEquals(second, sink.lastEntry)
    }

    @Test
    fun handsOutASnapshotThatLaterLinesDoNotChange() {
        sink.record(entry(1L, "before"))

        val snapshot = sink.entries
        sink.record(entry(2L, "after"))

        assertEquals(1, snapshot.size)
        assertEquals(2, sink.entries.size)
    }

    @Test
    fun forgetsEverythingOnClearSoOneSinkCanServeSeveralCases() {
        sink.record(entry(1L, "before"))

        sink.clear()

        assertEquals(emptyList<LogEntry>(), sink.entries)
        assertNull(sink.lastEntry)
    }

    private fun entry(
        seq: Long,
        message: String,
        level: LogLevel = LogLevel.INFO,
    ): LogEntry = LogEntry(seq = seq, timeMs = seq, level = level, module = "test", message = message)
}
