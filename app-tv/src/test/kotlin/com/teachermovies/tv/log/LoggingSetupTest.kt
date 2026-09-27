package com.teachermovies.tv.log

import com.teachermovies.core.log.AppLog
import com.teachermovies.core.log.LogLevel
import com.teachermovies.core.log.RingBufferLogSink
import com.teachermovies.core.log.fake.RecordingLogSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LoggingSetupTest {
    private val logcat = RecordingLogSink()
    private val buffer = RingBufferLogSink()
    private val savedMinLevel = AppLog.minLevel
    private val savedDefault = Thread.getDefaultUncaughtExceptionHandler()

    @After
    fun leaveTheProcessAsItWasFound() {
        AppLog.uninstall(logcat)
        AppLog.uninstall(buffer)
        AppLog.minLevel = savedMinLevel
        Thread.setDefaultUncaughtExceptionHandler(savedDefault)
    }

    @Test
    fun sendsEveryLineToLogcatAndToTheBuffer() {
        LoggingSetup.install(debugBuild = false, buffer = buffer, logcat = logcat)

        AppLog.i("player", "opened")

        assertEquals(listOf("opened"), logcat.messages)
        assertEquals(listOf("opened"), buffer.entries().map { it.message })
    }

    @Test
    fun aReleaseBuildDropsDebugLines() {
        LoggingSetup.install(debugBuild = false, buffer = buffer, logcat = logcat)

        AppLog.d("player", "dropped")
        AppLog.i("player", "kept")

        assertEquals(LogLevel.INFO, AppLog.minLevel)
        assertEquals(listOf("kept"), logcat.messages)
    }

    @Test
    fun aDebugBuildKeepsDebugLines() {
        LoggingSetup.install(debugBuild = true, buffer = buffer, logcat = logcat)

        AppLog.d("player", "kept")

        assertEquals(LogLevel.DEBUG, AppLog.minLevel)
        assertEquals(listOf("kept"), logcat.messages)
    }

    @Test
    fun installsTheCrashHandler() {
        val installed = LoggingSetup.install(debugBuild = false, buffer = buffer, logcat = logcat)

        assertSame(installed, Thread.getDefaultUncaughtExceptionHandler())
    }
}
