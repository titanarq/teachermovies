package com.teachermovies.http

import com.teachermovies.core.log.LogEntry
import com.teachermovies.core.log.LogLevel
import com.teachermovies.core.log.RingBufferLogSink

/** Boot id the log route tests (#269) fix their buffer to, so bodies can be asserted verbatim. */
internal const val LOGS_BOOT_ID = "boot-test"

/** Module tag the shared fixture stamps on every line. */
internal const val LOGS_MODULE = "torrent"

/** Epoch millis of fixture line `seq 0`; the line with `seq` is stamped with this plus `seq`. */
internal const val LOGS_TIME_MS = 1_700_000_000_000L

/** Records a deterministic fixture line, the way `RingBufferLogSinkTest` builds its entries. */
internal fun RingBufferLogSink.recordTestEntry(
    seq: Long,
    level: LogLevel = LogLevel.INFO,
    message: String = "line $seq",
) {
    record(
        LogEntry(seq = seq, timeMs = LOGS_TIME_MS + seq, level = level, module = LOGS_MODULE, message = message),
    )
}

/** The exact JSON a `LogEntryDto` has for a fixture line recorded through [recordTestEntry]. */
internal fun logEntryJson(
    seq: Long,
    level: String,
    message: String = "line $seq",
): String =
    """{"seq":$seq,"timeMs":${LOGS_TIME_MS + seq},"level":"$level","module":"$LOGS_MODULE","message":"$message"}"""
