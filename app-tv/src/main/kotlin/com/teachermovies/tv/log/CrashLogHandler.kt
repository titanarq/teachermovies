package com.teachermovies.tv.log

import com.teachermovies.core.log.AppLog

/**
 * Records an uncaught exception at ERROR, stack trace included, before the process dies (#268), then
 * hands it to [previous] -- on Android the platform handler that reports the crash and kills the
 * process -- so the crash itself behaves exactly as it did without this handler.
 *
 * [previous] is called even when logging throws: a broken sink must never turn a crash into a hung
 * thread. With no previous handler the thread just dies, as it would have.
 */
class CrashLogHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(
        thread: Thread,
        error: Throwable,
    ) {
        try {
            AppLog.e(LOG_MODULE, "Uncaught exception on thread \"${thread.name}\"; the process is going down", error)
        } finally {
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val LOG_MODULE = "app-tv"

        /** Makes a [CrashLogHandler] the default handler, chained to whichever one was there before. */
        fun install(): CrashLogHandler =
            CrashLogHandler(Thread.getDefaultUncaughtExceptionHandler())
                .also(Thread::setDefaultUncaughtExceptionHandler)
    }
}
