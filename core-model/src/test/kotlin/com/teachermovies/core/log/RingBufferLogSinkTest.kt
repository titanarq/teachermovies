package com.teachermovies.core.log

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RingBufferLogSinkTest {
    @Test
    fun keepsAtMostTheFiveThousandLinesOfAdr0006() {
        val sink = RingBufferLogSink()

        (1L..RingBufferLogSink.MAX_LINES + 1L).forEach { sink.record(line(it)) }

        val kept = sink.entries(limit = RingBufferLogSink.MAX_LINES + 1)
        assertEquals(RingBufferLogSink.MAX_LINES, kept.size)
        assertEquals("the oldest line is the one that goes", 2L, kept.first().seq)
        assertEquals(RingBufferLogSink.MAX_LINES + 1L, kept.last().seq)
    }

    @Test
    fun keepsAtMostOneMebibyteOfMessageText() {
        val sink = RingBufferLogSink()
        val twoKibibytes = "x".repeat(RingBufferLogSink.MAX_MESSAGE_BYTES)

        repeat(EXACTLY_ONE_MEBIBYTE_OF_TWO_KIBIBYTE_LINES + OVERFLOW_LINES) { index ->
            sink.record(line(index + 1L, message = twoKibibytes))
        }

        val kept = sink.entries(limit = EXACTLY_ONE_MEBIBYTE_OF_TWO_KIBIBYTE_LINES + OVERFLOW_LINES)
        val storedBytes = kept.sumOf { it.message.toByteArray(Charsets.UTF_8).size }
        assertEquals(EXACTLY_ONE_MEBIBYTE_OF_TWO_KIBIBYTE_LINES, kept.size)
        assertTrue("stored $storedBytes bytes", storedBytes <= RingBufferLogSink.MAX_BYTES)
        assertEquals(
            "the oldest lines went first",
            (OVERFLOW_LINES + 1L..OVERFLOW_LINES + EXACTLY_ONE_MEBIBYTE_OF_TWO_KIBIBYTE_LINES.toLong()).toList(),
            kept.map { it.seq },
        )
    }

    @Test
    fun evictsByLinesBeforeTheByteCapIsReached() {
        val sink = RingBufferLogSink(maxLines = 3)

        repeat(10) { index -> sink.record(line(index + 1L)) }

        assertEquals(listOf(8L, 9L, 10L), sink.entries(limit = 10).map { it.seq })
    }

    @Test
    fun cutsAMessageLongerThanTwoKibibytesAndMarksTheCut() {
        val sink = RingBufferLogSink()

        sink.record(line(1L, message = "y".repeat(RingBufferLogSink.MAX_MESSAGE_BYTES * 3)))

        val stored = sink.entries().single().message
        assertEquals(RingBufferLogSink.MAX_MESSAGE_BYTES, stored.toByteArray(Charsets.UTF_8).size)
        assertTrue(stored.endsWith(TRUNCATION_SUFFIX))
        assertTrue("what fits is kept from the start", stored.startsWith("yyy"))
    }

    @Test
    fun cutsOnACodePointBoundarySoTheStoredTextIsValidUtf8() {
        val sink = RingBufferLogSink()

        sink.record(line(1L, message = "🙂".repeat(RingBufferLogSink.MAX_MESSAGE_BYTES)))

        val stored = sink.entries().single().message
        assertTrue(stored.toByteArray(Charsets.UTF_8).size <= RingBufferLogSink.MAX_MESSAGE_BYTES)
        assertTrue(stored.endsWith(TRUNCATION_SUFFIX))
        assertFalse("a cut surrogate pair would decode as U+FFFD", stored.contains('\uFFFD'))
    }

    @Test
    fun pagesForwardFromTheCursorOldestFirst() {
        val sink = filledSink(10L)

        assertEquals((1L..10L).toList(), sink.entries(limit = 10).map { it.seq })
        assertEquals((4L..10L).toList(), sink.entries(since = 3L, limit = 10).map { it.seq })
        assertEquals(emptyList<Long>(), sink.entries(since = 10L, limit = 10).map { it.seq })
    }

    @Test
    fun limitsAPageSoTheCallerCanAskAgainFromTheLastSeqItGot() {
        val sink = filledSink(10L)

        assertEquals(listOf(1L, 2L, 3L), sink.entries(limit = 3).map { it.seq })
        assertEquals(listOf(4L, 5L, 6L), sink.entries(since = 3L, limit = 3).map { it.seq })
        assertEquals(listOf(7L, 8L, 9L), sink.entries(since = 6L, limit = 3).map { it.seq })
    }

    @Test
    fun servesAWholeBufferAsPagesOfTheDefaultSize() {
        val sink = filledSink(DEFAULT_PAGE_LINES + 100L)

        val firstPage = sink.entries()
        val secondPage = sink.entries(since = firstPage.last().seq)

        assertEquals(RingBufferLogSink.DEFAULT_PAGE, firstPage.size)
        assertEquals(100, secondPage.size)
        assertEquals(DEFAULT_PAGE_LINES + 100L, secondPage.last().seq)
    }

    @Test
    fun narrowsAPageToALevelAndAbove() {
        val sink = RingBufferLogSink()
        sink.record(line(1L, level = LogLevel.DEBUG))
        sink.record(line(2L, level = LogLevel.INFO))
        sink.record(line(3L, level = LogLevel.WARN))
        sink.record(line(4L, level = LogLevel.ERROR))

        assertEquals((1L..4L).toList(), sink.entries(minLevel = LogLevel.DEBUG).map { it.seq })
        assertEquals((2L..4L).toList(), sink.entries(minLevel = LogLevel.INFO).map { it.seq })
        assertEquals(listOf(3L, 4L), sink.entries(minLevel = LogLevel.WARN).map { it.seq })
        assertEquals(listOf(4L), sink.entries(minLevel = LogLevel.ERROR).map { it.seq })
        assertEquals((1L..4L).toList(), sink.entries().map { it.seq })
    }

    @Test
    fun combinesTheCursorTheLevelAndTheLimit() {
        val sink = RingBufferLogSink()
        repeat(20) { index ->
            val level = if (index % 2 == 0) LogLevel.DEBUG else LogLevel.INFO
            sink.record(line(index + 1L, level = level))
        }

        val page = sink.entries(since = 6L, minLevel = LogLevel.INFO, limit = 3)

        assertEquals(listOf(8L, 10L, 12L), page.map { it.seq })
    }

    @Test
    fun rejectsANonPositiveLimit() {
        val sink = filledSink(3L)

        assertThrows(IllegalArgumentException::class.java) { sink.entries(limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { sink.entries(limit = -1) }
    }

    @Test
    fun rejectsCapsThatCouldNotHoldAnything() {
        assertThrows(IllegalArgumentException::class.java) { RingBufferLogSink(maxLines = 0) }
        assertThrows(IllegalArgumentException::class.java) { RingBufferLogSink(maxBytes = 0L) }
        assertThrows(IllegalArgumentException::class.java) { RingBufferLogSink(maxMessageBytes = 8) }
    }

    @Test
    fun redactsBeforeStoring() {
        val redactor = LogRedactor().apply { registerSecret(PIN) }
        val sink = RingBufferLogSink(redactor = redactor)

        sink.record(line(1L, message = "paired with token=$TOKEN, PIN was $PIN"))

        val stored = sink.entries().single().message
        assertEquals("paired with token=$REDACTED, PIN was $REDACTED", stored)
        assertFalse(stored.contains(TOKEN))
        assertFalse(stored.contains(PIN))
    }

    @Test
    fun publishesEveryStoredLineOnTheFlowAlreadyRedacted() =
        runTest {
            val sink = RingBufferLogSink()
            val published = mutableListOf<LogEntry>()
            // Unconfined: a collector on the standard test dispatcher is still not subscribed when
            // the lines below are recorded, and a SharedFlow keeps nothing for a latecomer.
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sink.entries.toList(published) }

            sink.record(line(1L, message = "token=$TOKEN"))
            sink.record(line(2L, message = "downloaded 42%"))

            assertEquals(listOf(1L, 2L), published.map { it.seq })
            assertEquals("token=$REDACTED", published.first().message)
            assertEquals(published, sink.entries())
        }

    @Test
    fun publishesNothingToACollectorThatArrivesLate() =
        runTest {
            val sink = filledSink(3L)
            val published = mutableListOf<LogEntry>()

            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sink.entries.toList(published) }
            testScheduler.advanceUntilIdle()

            assertEquals("the backlog is what entries(since) is for", emptyList<LogEntry>(), published)
        }

    @Test
    fun keepsTheSameBootIdForTheWholeBufferAndGivesEverySinkItsOwn() {
        val sink = RingBufferLogSink()
        val bootId = sink.bootId

        sink.record(line(1L))
        sink.record(line(2L))

        assertEquals(bootId, sink.bootId)
        assertNotEquals(bootId, RingBufferLogSink().bootId)
        // A caller can supply the id, which is what a test does and what lets the bridge be tested.
        assertEquals("boot-1", RingBufferLogSink(bootId = "boot-1").bootId)
    }

    @Test
    fun storesWhatItIsHandedWhenThereIsNothingToRedactOrCut() {
        val entry = line(7L, message = "downloaded 42% of Inception.mkv")
        val sink = RingBufferLogSink(bootId = "boot-1")

        sink.record(entry)

        assertSame(entry, sink.entries().single())
    }

    @Test
    fun keepsEveryLineWhenSeveralThreadsLogAtOnce() {
        val sink = RingBufferLogSink()
        val threads = List(THREADS) { thread -> Thread { logABlock(thread, sink) } }

        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        val kept = sink.entries(limit = THREADS * LINES_PER_THREAD)
        assertEquals(THREADS * LINES_PER_THREAD, kept.size)
        assertEquals(THREADS * LINES_PER_THREAD, kept.map { it.seq }.distinct().size)
    }

    /** Each thread logs its own block of the sequence, so no two lines can share a number. */
    private fun logABlock(
        thread: Int,
        sink: RingBufferLogSink,
    ) {
        val firstSeq = thread * LINES_PER_THREAD + 1L
        repeat(LINES_PER_THREAD) { index -> sink.record(line(firstSeq + index)) }
    }

    private fun filledSink(lines: Long): RingBufferLogSink {
        val sink = RingBufferLogSink()
        (1L..lines).forEach { sink.record(line(it)) }
        return sink
    }

    private fun line(
        seq: Long,
        message: String = "line $seq",
        level: LogLevel = LogLevel.INFO,
    ): LogEntry = LogEntry(seq = seq, timeMs = FIRST_TIME_MS + seq, level = level, module = MODULE, message = message)

    private companion object {
        const val MODULE = "test"
        const val FIRST_TIME_MS = 1_700_000_000_000L
        const val TRUNCATION_SUFFIX = "[truncated]"
        const val REDACTED = LogRedactor.REDACTED
        const val TOKEN = "abcdefghijabcdefghijabcdefghijabcdefghijxyz"
        const val PIN = "123456"
        const val EXACTLY_ONE_MEBIBYTE_OF_TWO_KIBIBYTE_LINES = 512
        const val OVERFLOW_LINES = 88
        const val DEFAULT_PAGE_LINES = RingBufferLogSink.DEFAULT_PAGE
        const val THREADS = 4
        const val LINES_PER_THREAD = 250
    }
}
