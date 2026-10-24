package com.teachermovies.tv.player

import android.view.KeyEvent

/**
 * Tells a short press of DPAD_UP from a hold, to toggle the player mode with the hold (#385).
 * Pure: the caller passes each key event's code, repeat count and time, so it runs in a JVM test.
 *
 * UP is consumed in both modes. Its key-down only starts the measurement; the short-press action
 * (the menu, in Teacher Movies mode) runs when the key goes up without a toggle, because at
 * key-down a short press cannot yet be told from the start of a hold. A hold toggles exactly once,
 * the first time [holdMs] have passed since the first key-down, however many repeats follow; the
 * key-up that ends it does nothing.
 */
class ModeToggleDetector(
    private val holdMs: Long = HOLD_MS,
) {
    /** What the screen must do with one UP event. */
    enum class Outcome {
        /** Not a key this detector knows: handle it elsewhere. */
        NOT_HANDLED,

        /** UP, consumed, nothing to do yet. */
        CONSUMED,

        /** UP held long enough: toggle the mode now. */
        TOGGLE,

        /** UP released before the hold: run the short-press action. */
        SHORT_PRESS,
    }

    private var downAtMs: Long? = null
    private var fired = false

    fun onKeyDown(
        keyCode: Int,
        repeatCount: Int,
        eventTimeMs: Long,
    ): Outcome {
        if (keyCode != KeyEvent.KEYCODE_DPAD_UP) return Outcome.NOT_HANDLED
        if (repeatCount == 0 || downAtMs == null) {
            downAtMs = eventTimeMs
            fired = false
        }
        val start = downAtMs ?: eventTimeMs
        if (!fired && eventTimeMs - start >= holdMs) {
            fired = true
            return Outcome.TOGGLE
        }
        return Outcome.CONSUMED
    }

    fun onKeyUp(keyCode: Int): Outcome {
        if (keyCode != KeyEvent.KEYCODE_DPAD_UP) return Outcome.NOT_HANDLED
        val wasHold = fired || downAtMs == null
        downAtMs = null
        fired = false
        return if (wasHold) Outcome.CONSUMED else Outcome.SHORT_PRESS
    }

    companion object {
        /** How long UP must be held to toggle the mode. */
        const val HOLD_MS = 600L
    }
}
