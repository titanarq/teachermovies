package com.teachermovies.player.streaming

import org.junit.Assert.assertEquals
import org.junit.Test

/** Underrun 2 MiB, resume 12 MiB, as in [StreamPolicy.DEFAULT]. */
class BufferDecisionTest {
    private val policy = StreamPolicy.DEFAULT
    private val mib = 1024L * 1024L

    @Test
    fun playStaysPlayAboveTheUnderrunThreshold() {
        assertEquals(BufferDecision.Play, policy.decide(BufferDecision.Play, 3 * mib, atEndOfFile = false))
        assertEquals(BufferDecision.Play, policy.decide(BufferDecision.Play, 2 * mib, atEndOfFile = false))
    }

    @Test
    fun playBecomesWaitBelowTheUnderrunThreshold() {
        assertEquals(BufferDecision.Wait, policy.decide(BufferDecision.Play, 2 * mib - 1, atEndOfFile = false))
    }

    @Test
    fun waitStaysWaitBetweenTheThresholds() {
        assertEquals(BufferDecision.Wait, policy.decide(BufferDecision.Wait, 2 * mib, atEndOfFile = false))
        assertEquals(BufferDecision.Wait, policy.decide(BufferDecision.Wait, 12 * mib - 1, atEndOfFile = false))
    }

    @Test
    fun waitBecomesPlayAtTheResumeThreshold() {
        assertEquals(BufferDecision.Play, policy.decide(BufferDecision.Wait, 12 * mib, atEndOfFile = false))
    }

    @Test
    fun waitBecomesPlayAtEndOfFileRegardlessOfReadyBytes() {
        assertEquals(BufferDecision.Play, policy.decide(BufferDecision.Wait, 0, atEndOfFile = true))
        assertEquals(BufferDecision.Play, policy.decide(BufferDecision.Play, 0, atEndOfFile = true))
    }
}
