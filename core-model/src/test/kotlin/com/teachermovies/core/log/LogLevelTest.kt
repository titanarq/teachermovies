package com.teachermovies.core.log

import org.junit.Assert.assertEquals
import org.junit.Test

class LogLevelTest {
    /** What each minimum keeps, restated here so the test owns the expectation. */
    private val keptByMinimum =
        mapOf(
            LogLevel.DEBUG to LogLevel.entries,
            LogLevel.INFO to listOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR),
            LogLevel.WARN to listOf(LogLevel.WARN, LogLevel.ERROR),
            LogLevel.ERROR to listOf(LogLevel.ERROR),
        )

    @Test
    fun ordersTheLevelsFromLeastToMostSevere() {
        assertEquals(listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR), LogLevel.entries)
    }

    @Test
    fun declaresARuleForEveryMinimum() {
        assertEquals(LogLevel.entries.toSet(), keptByMinimum.keys)
    }

    @Test
    fun keepsEveryLevelAtOrAboveTheMinimumAndDropsTheRest() {
        keptByMinimum.forEach { (minimum, kept) ->
            LogLevel.entries.forEach { level ->
                assertEquals("$level with minimum $minimum", level in kept, level.isAtLeast(minimum))
            }
        }
    }
}
