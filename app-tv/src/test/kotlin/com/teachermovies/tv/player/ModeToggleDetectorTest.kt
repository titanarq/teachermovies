package com.teachermovies.tv.player

import android.view.KeyEvent
import com.teachermovies.tv.player.ModeToggleDetector.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class ModeToggleDetectorTest {
    private val down = KeyEvent.KEYCODE_DPAD_DOWN
    private val detector = ModeToggleDetector(holdMs = 600L)

    @Test
    fun otherKeysAreNotHandled() {
        assertEquals(Outcome.NOT_HANDLED, detector.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, 0, 0L))
        assertEquals(Outcome.NOT_HANDLED, detector.onKeyUp(KeyEvent.KEYCODE_DPAD_LEFT))
    }

    @Test
    fun aQuickPressIsAShortPressOnKeyUp() {
        assertEquals(Outcome.CONSUMED, detector.onKeyDown(down, 0, 1_000L))
        assertEquals(Outcome.SHORT_PRESS, detector.onKeyUp(down))
    }

    @Test
    fun aHoldTogglesExactlyOnceNoMatterHowManyRepeatsFollow() {
        val outcomes =
            listOf(0 to 0L, 1 to 300L, 2 to 599L, 3 to 650L, 4 to 700L, 5 to 2_000L)
                .map { (repeat, at) -> detector.onKeyDown(down, repeat, at) }

        assertEquals(
            listOf(
                Outcome.CONSUMED,
                Outcome.CONSUMED,
                Outcome.CONSUMED,
                Outcome.TOGGLE,
                Outcome.CONSUMED,
                Outcome.CONSUMED,
            ),
            outcomes,
        )
        assertEquals("the release after a hold is not a short press", Outcome.CONSUMED, detector.onKeyUp(down))
    }

    @Test
    fun aSecondHoldTogglesAgain() {
        detector.onKeyDown(down, 0, 0L)
        assertEquals(Outcome.TOGGLE, detector.onKeyDown(down, 1, 700L))
        detector.onKeyUp(down)

        detector.onKeyDown(down, 0, 5_000L)
        assertEquals(Outcome.TOGGLE, detector.onKeyDown(down, 1, 5_700L))
    }

    @Test
    fun aShortPressAfterAHoldIsAShortPressAgain() {
        detector.onKeyDown(down, 0, 0L)
        detector.onKeyDown(down, 1, 700L)
        detector.onKeyUp(down)

        detector.onKeyDown(down, 0, 5_000L)
        assertEquals(Outcome.SHORT_PRESS, detector.onKeyUp(down))
    }
}
