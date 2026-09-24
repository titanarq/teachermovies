package com.teachermovies.tv.player

import android.view.KeyEvent

/**
 * What a remote key asks the English-learning assistant to do on the player screen (#86). The
 * screen asks [AssistantKeyMapper] first and only falls through to [RemoteKeyMapper] when it
 * returns null.
 */
sealed interface AssistantAction {
    /** Pause the movie and show the English line that was just spoken. */
    data object CaptureLine : AssistantAction

    /** Play the captured line's original audio fragment again. */
    data object ReplayFragment : AssistantAction

    /** Close the captured-line overlay and resume the movie. */
    data object DismissOverlay : AssistantAction

    /** A key pressed while the overlay is open that means nothing to it: swallowed, no effect. */
    data object Consumed : AssistantAction
}

/**
 * Pure mapping from a remote key code to an [AssistantAction] (#86).
 *
 * With the overlay closed only the capture keys (DPAD_DOWN, CAPTIONS) are taken; every other key is
 * null so the transport mapping of [RemoteKeyMapper] keeps handling it. With the overlay open every
 * key is taken: OK/ENTER/PLAY_PAUSE replay, BACK/DPAD_DOWN/CAPTIONS dismiss, and the rest is
 * [AssistantAction.Consumed], so no transport action can run behind the overlay.
 */
object AssistantKeyMapper {
    fun map(
        keyCode: Int,
        overlayOpen: Boolean,
    ): AssistantAction? =
        if (overlayOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                -> AssistantAction.ReplayFragment
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_CAPTIONS,
                -> AssistantAction.DismissOverlay
                else -> AssistantAction.Consumed
            }
        } else {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_CAPTIONS,
                -> AssistantAction.CaptureLine
                else -> null
            }
        }
}
