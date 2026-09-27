package com.teachermovies.core.log

import com.teachermovies.core.log.fake.RecordingLogSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppLogTest {
    private val sink = RecordingLogSink()
    private val secondSink = RecordingLogSink()
    private val savedMinLevel = AppLog.minLevel
    private val savedClock = AppLog.clock

    @Before
    fun installTheSinkAndFreezeTheClock() {
        AppLog.minLevel = LogLevel.DEBUG
        AppLog.clock = { NOW }
        AppLog.install(sink)
    }

    @After
    fun leaveTheFacadeAsItWasFound() {
        AppLog.uninstall(sink)
        AppLog.uninstall(secondSink)
        AppLog.minLevel = savedMinLevel
        AppLog.clock = savedClock
    }

    @Test
    fun sendsEveryLevelToTheInstalledSink() {
        AppLog.d(MODULE, "a debug line")
        AppLog.i(MODULE, "an info line")
        AppLog.w(MODULE, "a warning line")
        AppLog.e(MODULE, "an error line")

        assertEquals(
            listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR),
            sink.entries.map { it.level },
        )
        assertEquals(
            listOf("a debug line", "an info line", "a warning line", "an error line"),
            sink.messages,
        )
        assertEquals(List(4) { MODULE }, sink.entries.map { it.module })
    }

    @Test
    fun numbersEveryAcceptedLineInOrder() {
        AppLog.i(MODULE, "one")
        AppLog.i(MODULE, "two")
        AppLog.i(MODULE, "three")

        val seqs = sink.entries.map { it.seq }

        assertEquals(3, seqs.distinct().size)
        assertEquals("the sequence only grows", seqs.sorted(), seqs)
    }

    @Test
    fun stampsEveryLineWithTheTimeTheClockSays() {
        var now = NOW
        AppLog.clock = { now }

        AppLog.i(MODULE, "before")
        now += 1_500L
        AppLog.i(MODULE, "after")

        assertEquals(listOf(NOW, NOW + 1_500L), sink.entries.map { it.timeMs })
    }

    @Test
    fun dropsLinesBelowTheMinimumLevelBeforeAnySinkSeesThem() {
        AppLog.minLevel = LogLevel.INFO

        AppLog.d(MODULE, "dropped")
        AppLog.i(MODULE, "kept")
        AppLog.w(MODULE, "kept too")
        AppLog.e(MODULE, "kept as well")

        assertEquals(listOf("kept", "kept too", "kept as well"), sink.messages)
    }

    @Test
    fun spendsNoSequenceNumberOnADroppedLine() {
        AppLog.minLevel = LogLevel.ERROR
        AppLog.d(MODULE, "dropped")
        AppLog.w(MODULE, "dropped too")

        AppLog.e(MODULE, "kept")
        val first = sink.entries.single().seq
        AppLog.e(MODULE, "kept too")

        assertEquals(listOf(first, first + 1L), sink.entries.map { it.seq })
    }

    @Test
    fun appendsTheStackTraceOfAnErrorSoTheBufferKeepsWhatACrashLookedLike() {
        val failure = IllegalStateException("libVLC refused the file")

        AppLog.e(MODULE, "playback failed", failure)

        val message = sink.messages.single()
        assertTrue(message.startsWith("playback failed\n"))
        assertTrue(message.contains("java.lang.IllegalStateException: libVLC refused the file"))
        assertTrue("the frames say where it happened", message.contains("at com.teachermovies.core.log.AppLogTest"))
    }

    @Test
    fun logsAnErrorWithoutAThrowableAsTheMessageAlone() {
        AppLog.e(MODULE, "download failed")

        assertEquals("download failed", sink.messages.single())
    }

    @Test
    fun sendsTheSameLineToEveryInstalledSink() {
        AppLog.install(secondSink)

        AppLog.w(MODULE, "the download volume is almost full")

        assertEquals(sink.entries, secondSink.entries)
        assertEquals("the download volume is almost full", secondSink.messages.single())
    }

    @Test
    fun installingTheSameSinkTwiceStillRecordsEachLineOnce() {
        AppLog.install(sink)

        AppLog.i(MODULE, "one line")

        assertEquals(1, sink.entries.size)
    }

    @Test
    fun stopsSendingLinesToAnUninstalledSink() {
        AppLog.uninstall(sink)

        AppLog.i(MODULE, "nobody is listening")

        assertEquals(emptyList<String>(), sink.messages)
    }

    @Test
    fun leavesRedactionToTheSinkThatStoresTheLine() {
        AppLog.i(MODULE, "pairing accepted with token=$TOKEN")

        assertEquals("pairing accepted with token=$TOKEN", sink.messages.single())
    }

    @Test
    fun anInstalledRingBufferStoresTheLineRedactedAndNumbered() {
        val buffer = RingBufferLogSink(bootId = "boot-1")
        AppLog.install(buffer)
        try {
            AppLog.i(MODULE, "pairing accepted with token=$TOKEN")

            val stored = buffer.entries().single()
            val dispatched = sink.entries.single()
            assertEquals("pairing accepted with token=${LogRedactor.REDACTED}", stored.message)
            assertEquals(dispatched.seq, stored.seq)
            assertEquals(dispatched.timeMs, stored.timeMs)
            assertEquals(MODULE, stored.module)
            assertEquals(LogLevel.INFO, stored.level)
        } finally {
            AppLog.uninstall(buffer)
        }
    }

    private companion object {
        const val MODULE = "test"
        const val NOW = 1_700_000_000_000L
        const val TOKEN = "abcdefghijabcdefghijabcdefghijabcdefghijxyz"
    }
}
