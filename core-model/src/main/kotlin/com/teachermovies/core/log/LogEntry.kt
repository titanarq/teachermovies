package com.teachermovies.core.log

/**
 * One log line as [AppLog] hands it to a [LogSink], and as [RingBufferLogSink] stores it (ADR-0006).
 *
 * [seq] is the process-wide counter [AppLog] keeps: it grows by one per accepted line and is the
 * cursor `GET /api/logs?since=` pages on, so a reader asks for "everything after the last seq I
 * saw". It says nothing across a restart -- [RingBufferLogSink.bootId] changing is what tells a
 * reader to start over from `since = 0`. [timeMs] is wall-clock epoch millis. [module] is the part
 * of the app that logged (`player`, `torrent`, `http`...). [message] is the text itself, already
 * redacted and cut to the per-line cap by the time a sink stores it.
 */
data class LogEntry(
    val seq: Long,
    val timeMs: Long,
    val level: LogLevel,
    val module: String,
    val message: String,
)
