package com.teachermovies.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class KeepScreenOnControllerTest {
    private val calls = mutableListOf<Boolean>()
    private val controller = KeepScreenOnController { calls += it }

    @Test
    fun playingSetsTheFlag() {
        controller.apply(isPlaying = true)
        assertEquals(listOf(true), calls)
    }

    @Test
    fun pausedClearsTheFlag() {
        controller.apply(isPlaying = true)
        controller.apply(isPlaying = false)
        assertEquals(listOf(true, false), calls)
    }

    @Test
    fun disposeClearsTheFlag() {
        controller.apply(isPlaying = true)
        controller.release()
        assertEquals(false, calls.last())
    }
}
