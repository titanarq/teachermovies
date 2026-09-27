package com.teachermovies.tv.log

import android.util.Log
import com.teachermovies.core.log.LogEntry
import com.teachermovies.core.log.LogLevel
import com.teachermovies.core.log.LogSink

/**
 * The logcat half of the app's logging (ADR-0006): hands every line [com.teachermovies.core.log.AppLog]
 * accepts to `android.util.Log`, tagged `TM/<module>` so `adb logcat -s TM/player` still works.
 *
 * The only place in the app that names `android.util.Log`: everything else logs through `AppLog`
 * (#268). It writes the line as logged, unredacted, like the raw `Log` calls it replaced; only the
 * ring buffer that leaves the device redacts. [write] is `Log.println` in production and a recorder
 * in a JVM test, where `android.util.Log` is a stub.
 */
class AndroidLogSink(
    private val write: (priority: Int, tag: String, message: String) -> Unit = { priority, tag, message ->
        Log.println(priority, tag, message)
    },
) : LogSink {
    override fun record(entry: LogEntry) {
        write(priorityOf(entry.level), tagOf(entry.module), entry.message)
    }

    companion object {
        private const val TAG_PREFIX = "TM/"

        /** The logcat tag of a line from [module]. */
        fun tagOf(module: String): String = TAG_PREFIX + module

        /** The `android.util.Log` priority a [LogLevel] is written at. */
        fun priorityOf(level: LogLevel): Int =
            when (level) {
                LogLevel.DEBUG -> Log.DEBUG
                LogLevel.INFO -> Log.INFO
                LogLevel.WARN -> Log.WARN
                LogLevel.ERROR -> Log.ERROR
            }
    }
}
