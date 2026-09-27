package com.teachermovies.core.log

/**
 * Where [AppLog] hands every line that passes its level filter (ADR-0006).
 *
 * [record] runs on the thread that logged, which is often a native one (the jlibtorrent alert loop,
 * a libVLC event callback), so a sink must not block: [RingBufferLogSink] bounds its own work under
 * a short lock and drops the oldest line when it is full.
 */
fun interface LogSink {
    fun record(entry: LogEntry)
}
