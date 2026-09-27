package com.teachermovies.core.log.fake

import com.teachermovies.core.log.LogEntry
import com.teachermovies.core.log.LogSink

/**
 * A [LogSink] that keeps every line it is handed, for JVM tests here and in any other module
 * (ADR-0003: fakes live in the main source set, in a `fake` package).
 *
 * Deterministic and transparent: it invents no time and no [LogEntry.seq] of its own and redacts
 * nothing, so a test asserting on [entries] sees exactly what [com.teachermovies.core.log.AppLog]
 * dispatched, or exactly the [LogEntry] it built itself.
 */
class RecordingLogSink : LogSink {
    private val recorded = mutableListOf<LogEntry>()

    /** Everything recorded, oldest first, as a snapshot safe to assert on. */
    val entries: List<LogEntry> get() = synchronized(recorded) { recorded.toList() }

    /** The recorded messages, oldest first -- the usual "what did it say" assertion. */
    val messages: List<String> get() = entries.map { it.message }

    /** The most recent line, or null when nothing has been recorded. */
    val lastEntry: LogEntry? get() = entries.lastOrNull()

    /** Forgets everything recorded, so one sink can serve several cases. */
    fun clear() {
        synchronized(recorded) { recorded.clear() }
    }

    override fun record(entry: LogEntry) {
        synchronized(recorded) { recorded.add(entry) }
    }
}
