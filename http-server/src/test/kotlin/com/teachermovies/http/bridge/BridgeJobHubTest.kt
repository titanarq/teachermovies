package com.teachermovies.http.bridge

import com.teachermovies.bridge.protocol.BridgeJobProtocol
import com.teachermovies.bridge.protocol.BridgeJobResultDto
import com.teachermovies.bridge.protocol.ExplainJobDto
import com.teachermovies.bridge.protocol.TranslateJobDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** The hub's own rules (#275), in virtual time: no sockets, the stream is read off its channel. */
@OptIn(ExperimentalCoroutinesApi::class)
class BridgeJobHubTest {
    private var nextId = 0
    private val hub = BridgeJobHub { "job-${++nextId}" }
    private val timeout = 10.seconds

    private fun BridgeStream.next(): BridgeStreamEvent? = events.tryReceive().getOrNull()

    @Test
    fun `with no bridge connected submit answers NoBridge at once and sends nothing`() =
        runTest {
            assertFalse(hub.connected.value)
            assertEquals(BridgeOutcome.NoBridge, hub.submit(BridgeJob.Translate("hi"), timeout))
            assertEquals(0L, currentTime)
            assertEquals(0, nextId)
        }

    @Test
    fun `connected follows the stream`() {
        val stream = hub.connect()
        assertTrue(hub.connected.value)
        hub.disconnect(stream)
        assertFalse(hub.connected.value)
    }

    @Test
    fun `a job goes out on the stream and resolves Done with the posted text`() =
        runTest {
            val stream = hub.connect()
            val outcome = async { hub.submit(BridgeJob.Translate("Break a leg!"), timeout) }
            runCurrent()
            assertEquals(BridgeStreamEvent.Job(TranslateJobDto("job-1", "Break a leg!")), stream.next())
            assertEquals(CompleteResult.ACCEPTED, hub.complete("job-1", BridgeJobResultDto.Done("¡Mucha suerte!")))
            assertEquals(BridgeOutcome.Done("¡Mucha suerte!"), outcome.await())
            // Single-use: a second answer is refused, and an id never issued is unknown.
            assertEquals(CompleteResult.CLOSED, hub.complete("job-1", BridgeJobResultDto.Done("again")))
            assertEquals(CompleteResult.UNKNOWN, hub.complete("job-99", BridgeJobResultDto.Done("x")))
        }

    @Test
    fun `an explain job carries its whole context`() =
        runTest {
            val stream = hub.connect()
            val job = BridgeJob.Explain("Heat", "line", listOf("a", "b", "c"), listOf("d", "e"), "línea")
            val outcome = async { hub.submit(job, timeout) }
            runCurrent()
            assertEquals(
                BridgeStreamEvent.Job(
                    ExplainJobDto("job-1", "Heat", "line", listOf("a", "b", "c"), listOf("d", "e"), "línea"),
                ),
                stream.next(),
            )
            hub.complete("job-1", BridgeJobResultDto.Failed("rate_limited", "429"))
            assertEquals(BridgeOutcome.BridgeError("rate_limited", "429"), outcome.await())
        }

    @Test
    fun `no answer within the timeout resolves TimedOut and cancels the job on the bridge`() =
        runTest {
            val stream = hub.connect()
            val outcome = async { hub.submit(BridgeJob.Translate("hi"), timeout) }
            runCurrent()
            stream.next() // the job
            advanceTimeBy(timeout.inWholeMilliseconds - 1)
            assertFalse(outcome.isCompleted)
            advanceTimeBy(2)
            assertEquals(BridgeOutcome.TimedOut, outcome.await())
            assertEquals(BridgeStreamEvent.Cancel("job-1"), stream.next())
            assertEquals(CompleteResult.CLOSED, hub.complete("job-1", BridgeJobResultDto.Done("late")))
        }

    @Test
    fun `a newer job for the same slot replaces the older one, which the bridge is told to cancel`() =
        runTest {
            val stream = hub.connect()
            val first = async { hub.submit(BridgeJob.Translate("one"), timeout) }
            runCurrent()
            val second = async { hub.submit(BridgeJob.Translate("two"), timeout) }
            runCurrent()
            assertEquals(BridgeOutcome.Replaced, first.await())
            assertEquals(BridgeStreamEvent.Job(TranslateJobDto("job-1", "one")), stream.next())
            assertEquals(BridgeStreamEvent.Cancel("job-1"), stream.next())
            assertEquals(BridgeStreamEvent.Job(TranslateJobDto("job-2", "two")), stream.next())
            assertEquals(CompleteResult.CLOSED, hub.complete("job-1", BridgeJobResultDto.Done("uno")))
            hub.complete("job-2", BridgeJobResultDto.Done("dos"))
            assertEquals(BridgeOutcome.Done("dos"), second.await())
        }

    @Test
    fun `jobs of different slots do not replace each other`() =
        runTest {
            hub.connect()
            val translate = async { hub.submit(BridgeJob.Translate("one"), timeout) }
            val explain = async { hub.submit(BridgeJob.Explain(null, "one", emptyList(), emptyList(), null), timeout) }
            runCurrent()
            hub.complete("job-1", BridgeJobResultDto.Done("t"))
            hub.complete("job-2", BridgeJobResultDto.Done("e"))
            assertEquals(BridgeOutcome.Done("t"), translate.await())
            assertEquals(BridgeOutcome.Done("e"), explain.await())
        }

    @Test
    fun `a caller that gives up cancels the job on the bridge`() =
        runTest {
            val stream = hub.connect()
            val outcome = async { hub.submit(BridgeJob.Translate("hi"), timeout) }
            runCurrent()
            stream.next()
            outcome.cancel()
            runCurrent()
            assertEquals(BridgeStreamEvent.Cancel("job-1"), stream.next())
            assertEquals(CompleteResult.CLOSED, hub.complete("job-1", BridgeJobResultDto.Done("late")))
        }

    @Test
    fun `a dropped stream resolves its jobs Disconnected`() =
        runTest {
            val stream = hub.connect()
            val outcome = async { hub.submit(BridgeJob.Translate("hi"), timeout) }
            runCurrent()
            hub.disconnect(stream)
            assertEquals(BridgeOutcome.Disconnected, outcome.await())
            assertFalse(hub.connected.value)
            assertEquals(BridgeOutcome.NoBridge, hub.submit(BridgeJob.Translate("again"), timeout))
        }

    @Test
    fun `the newest stream wins and the older one is closed`() =
        runTest {
            val older = hub.connect()
            val outcome = async { hub.submit(BridgeJob.Translate("hi"), timeout) }
            runCurrent()
            val newer = hub.connect()
            assertEquals(BridgeOutcome.Disconnected, outcome.await())
            older.next() // the job it got before being replaced
            assertTrue(older.events.isClosedForReceive)
            // The older route finishing afterwards must not mark the newer bridge gone.
            hub.disconnect(older)
            assertTrue(hub.connected.value)

            val next = async { hub.submit(BridgeJob.Translate("next"), timeout) }
            runCurrent()
            assertEquals(BridgeStreamEvent.Job(TranslateJobDto("job-2", "next")), newer.next())
            hub.complete("job-2", BridgeJobResultDto.Done("siguiente"))
            assertEquals(BridgeOutcome.Done("siguiente"), next.await())
        }

    @Test
    fun `a line over the limit is rejected and never sent`() =
        runTest {
            val stream = hub.connect()
            val atLimit = "a".repeat(BridgeJobProtocol.MAX_LINE_CHARS)
            val overLimit = atLimit + "a"
            assertTrue(hub.submit(BridgeJob.Translate(overLimit), timeout) is BridgeOutcome.Rejected)
            assertTrue(
                hub.submit(BridgeJob.Explain(null, "ok", listOf(overLimit), emptyList(), null), timeout) is
                    BridgeOutcome.Rejected,
            )
            assertNull(stream.next())

            val accepted = async { hub.submit(BridgeJob.Translate(atLimit), timeout) }
            runCurrent()
            assertTrue(stream.next() is BridgeStreamEvent.Job)
            accepted.cancel()
        }

    @Test
    fun `a job over the total payload limit is rejected and does not replace the older one`() =
        runTest {
            val stream = hub.connect()
            val first = async { hub.submit(BridgeJob.Explain(null, "one", emptyList(), emptyList(), null), timeout) }
            runCurrent()
            stream.next()
            // Nine lines of 500 chars: each within the line limit, together over 4 KB.
            val line = "a".repeat(BridgeJobProtocol.MAX_LINE_CHARS)
            val big = BridgeJob.Explain(line, line, List(3) { line }, List(2) { line }, line)
            assertTrue(hub.submit(big, timeout) is BridgeOutcome.Rejected)
            assertNull(stream.next())
            hub.complete("job-1", BridgeJobResultDto.Done("uno"))
            assertEquals(BridgeOutcome.Done("uno"), first.await())
        }
}
