package com.teachermovies.torrent.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThrottleTest {
    @Test
    fun `first value passes at once and later ones at most once per period`() =
        runTest {
            val source = MutableSharedFlow<Int>(extraBufferCapacity = 16)
            val seen = mutableListOf<Int>()
            val job = launch { source.throttleLatest(2_000).collect { seen += it } }
            runCurrent()

            source.emit(1)
            runCurrent()
            assertEquals(listOf(1), seen)

            advanceTimeBy(500)
            source.emit(2)
            advanceTimeBy(500)
            source.emit(3)
            runCurrent()
            assertEquals("nothing new inside the period", listOf(1), seen)

            advanceTimeBy(1_001)
            runCurrent()
            assertEquals("only the latest value after the period", listOf(1, 3), seen)

            advanceTimeBy(5_000)
            source.emit(4)
            runCurrent()
            assertEquals("an idle period lets the next value through at once", listOf(1, 3, 4), seen)
            job.cancel()
        }
}
