package com.teachermovies.bridge.run

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class BackoffTest {
    @Test
    fun `doubles up to the maximum and starts over after a reset`() {
        val backoff = Backoff(initial = 1.seconds, max = 5.seconds)
        assertEquals(listOf(1, 2, 4, 5, 5).map { it.seconds }, List(5) { backoff.next() })
        backoff.reset()
        assertEquals(1.seconds, backoff.next())
    }

    @Test
    fun `the default waits one second first and never more than a minute`() {
        val backoff = Backoff()
        assertEquals(1.seconds, backoff.next())
        repeat(20) { backoff.next() }
        assertEquals(60.seconds, backoff.next())
    }
}
