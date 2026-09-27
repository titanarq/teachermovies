package com.teachermovies.tv.log

import com.teachermovies.core.log.AppLog
import com.teachermovies.core.log.LogLevel
import com.teachermovies.core.log.LogSink
import com.teachermovies.core.log.fake.RecordingLogSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CrashLogHandlerTest {
    private val sink = RecordingLogSink()
    private val handedOn = mutableListOf<Pair<Thread, Throwable>>()
    private val previous = Thread.UncaughtExceptionHandler { thread, error -> handedOn += thread to error }
    private val savedDefault = Thread.getDefaultUncaughtExceptionHandler()

    @Before
    fun installTheSink() {
        AppLog.install(sink)
    }

    @After
    fun leaveTheProcessAsItWasFound() {
        AppLog.uninstall(sink)
        Thread.setDefaultUncaughtExceptionHandler(savedDefault)
    }

    @Test
    fun logsTheCrashAtErrorWithItsStackTraceThenChainsToThePreviousHandler() {
        val thread = Thread({}, "alert-loop")
        val crash = IllegalStateException("engine exploded")

        CrashLogHandler(previous).uncaughtException(thread, crash)

        val entry = sink.entries.single()
        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals(CrashLogHandler.LOG_MODULE, entry.module)
        assertTrue(entry.message, entry.message.contains("alert-loop"))
        assertTrue(entry.message, entry.message.contains("IllegalStateException: engine exploded"))
        assertTrue(entry.message, entry.message.contains("at "))
        assertEquals(listOf(thread to crash), handedOn)
    }

    @Test
    fun chainsToThePreviousHandlerEvenWhenLoggingThrows() {
        val broken = LogSink { throw IllegalStateException("sink broke") }
        AppLog.install(broken)
        val thread = Thread({}, "main")
        val crash = RuntimeException("boom")
        try {
            runCatching { CrashLogHandler(previous).uncaughtException(thread, crash) }
        } finally {
            AppLog.uninstall(broken)
        }

        assertEquals(listOf(thread to crash), handedOn)
    }

    @Test
    fun withNoPreviousHandlerOnlyLogs() {
        CrashLogHandler(previous = null).uncaughtException(Thread({}, "worker"), RuntimeException("boom"))

        assertEquals(1, sink.entries.size)
    }

    @Test
    fun installBecomesTheDefaultHandlerChainedToTheOneBefore() {
        Thread.setDefaultUncaughtExceptionHandler(previous)
        val thread = Thread({}, "main")
        val crash = RuntimeException("boom")

        val installed = CrashLogHandler.install()

        assertSame(installed, Thread.getDefaultUncaughtExceptionHandler())
        installed.uncaughtException(thread, crash)
        assertEquals(listOf(thread to crash), handedOn)
    }
}
