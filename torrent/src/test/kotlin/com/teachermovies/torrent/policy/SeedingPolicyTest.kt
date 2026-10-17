package com.teachermovies.torrent.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedingPolicyTest {
    @Test
    fun finishedAndRunningMustStop() = assertTrue(SeedingPolicy.shouldStopSeeding(isFinished = true, paused = false))

    @Test
    fun finishedAndPausedIsLeftAlone() = assertFalse(SeedingPolicy.shouldStopSeeding(isFinished = true, paused = true))

    @Test
    fun unfinishedAndRunningKeepsDownloading() =
        assertFalse(SeedingPolicy.shouldStopSeeding(isFinished = false, paused = false))

    @Test
    fun unfinishedAndPausedIsLeftAlone() =
        assertFalse(SeedingPolicy.shouldStopSeeding(isFinished = false, paused = true))
}
