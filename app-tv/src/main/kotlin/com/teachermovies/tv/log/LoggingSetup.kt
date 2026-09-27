package com.teachermovies.tv.log

import com.teachermovies.core.log.AppLog
import com.teachermovies.core.log.LogLevel
import com.teachermovies.core.log.LogSink
import com.teachermovies.core.log.RingBufferLogSink

/**
 * Starts the app's logging (ADR-0006, #268), once per process and before anything else in
 * `AppContainer` can log: DEBUG and above in a debuggable build, INFO and above otherwise; lines go
 * to logcat ([logcat]) and to [buffer], the ring buffer the HTTP API serves; and a [CrashLogHandler]
 * records an uncaught exception before the process dies.
 */
object LoggingSetup {
    fun install(
        debugBuild: Boolean,
        buffer: RingBufferLogSink,
        logcat: LogSink = AndroidLogSink(),
    ): CrashLogHandler {
        AppLog.minLevel = if (debugBuild) LogLevel.DEBUG else LogLevel.INFO
        AppLog.install(logcat)
        AppLog.install(buffer)
        return CrashLogHandler.install()
    }
}
