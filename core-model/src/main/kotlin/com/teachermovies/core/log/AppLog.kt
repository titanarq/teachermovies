package com.teachermovies.core.log

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The one logging facade of the app (ADR-0006): every module logs through it instead of
 * `android.util.Log`, and every line it accepts goes to each installed [LogSink].
 *
 * `AppLog` is an accepted global, the exception to the constructor injection of ADR-0003: a logger
 * handed through constructors would touch every class in the app for no decision a caller can make,
 * so the sinks are installed once, at startup, by `AppContainer` (a logcat sink plus the
 * [RingBufferLogSink] the HTTP API serves), and everything else just calls [d], [i], [w] or [e].
 *
 * The level filter lives here, not in the sinks, so a release build drops DEBUG before any sink
 * sees it: [minLevel] is INFO by default and `AppContainer` lowers it to DEBUG in a debug build.
 *
 * A line keeps its text: this facade does not redact. Redaction is [RingBufferLogSink]'s job, on
 * the way into the buffer that leaves the device -- callers still never log a secret (AGENTS.md).
 */
object AppLog {
    private val sinks = CopyOnWriteArrayList<LogSink>()
    private val sequence = AtomicLong(0L)

    /** The lowest level that reaches a sink; lines below it are dropped. */
    @Volatile
    var minLevel: LogLevel = LogLevel.INFO

    /**
     * Epoch-millis source for [LogEntry.timeMs]. Replace it to make a test deterministic (ADR-0003:
     * fakes use no real time); production leaves the default.
     */
    @Volatile
    var clock: () -> Long = System::currentTimeMillis

    /** Starts sending lines to [sink]. Installing the same sink twice has no effect. */
    fun install(sink: LogSink) {
        sinks.addIfAbsent(sink)
    }

    /** Stops sending lines to [sink]. */
    fun uninstall(sink: LogSink) {
        sinks.remove(sink)
    }

    fun d(
        module: String,
        message: String,
    ) = log(LogLevel.DEBUG, module, message)

    fun i(
        module: String,
        message: String,
    ) = log(LogLevel.INFO, module, message)

    fun w(
        module: String,
        message: String,
    ) = log(LogLevel.WARN, module, message)

    /**
     * Logs at [LogLevel.ERROR], appending [error]'s stack trace to [message] so the buffer keeps
     * what a crash looked like -- the uncaught-exception handler of #268 has nothing else to say it
     * with.
     */
    fun e(
        module: String,
        message: String,
        error: Throwable? = null,
    ) {
        log(LogLevel.ERROR, module, if (error == null) message else message + "\n" + error.stackTraceToString())
    }

    private fun log(
        level: LogLevel,
        module: String,
        message: String,
    ) {
        if (!level.isAtLeast(minLevel)) return
        val entry =
            LogEntry(
                seq = sequence.incrementAndGet(),
                timeMs = clock(),
                level = level,
                module = module,
                message = message,
            )
        sinks.forEach { it.record(entry) }
    }
}
