package com.teachermovies.tv.log

import android.util.Log
import com.teachermovies.core.log.LogEntry
import com.teachermovies.core.log.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidLogSinkTest {
    private data class Written(
        val priority: Int,
        val tag: String,
        val message: String,
    )

    private val written = mutableListOf<Written>()
    private val sink = AndroidLogSink { priority, tag, message -> written += Written(priority, tag, message) }

    @Test
    fun writesEachLevelAtItsLogcatPriority() {
        LogLevel.entries.forEachIndexed { i, level -> sink.record(entry(i + 1L, level, "line $i")) }

        assertEquals(listOf(Log.DEBUG, Log.INFO, Log.WARN, Log.ERROR), written.map { it.priority })
    }

    @Test
    fun tagsTheLineWithItsModuleAndKeepsTheMessage() {
        sink.record(entry(1L, LogLevel.WARN, "Torrent abc: odd transition"))

        assertEquals(listOf(Written(Log.WARN, "TM/torrent", "Torrent abc: odd transition")), written)
    }

    private fun entry(
        seq: Long,
        level: LogLevel,
        message: String,
    ) = LogEntry(seq = seq, timeMs = 0L, level = level, module = "torrent", message = message)
}
