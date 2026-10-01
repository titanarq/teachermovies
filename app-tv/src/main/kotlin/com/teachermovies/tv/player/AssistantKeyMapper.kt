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

    /**
     * Ask Claude on the laptop to explain the captured line and show it, never spoken (#293,
     * ADR-0005 §7); replaces the former "Escuchar" (#92).
     */
    data object ExplainLine : AssistantAction

    /**
     * Show the captured line in Spanish, never spoken (#288): the aligned Spanish subtitle, or the
     * bridge's translation when none aligns.
     */
    data object TranslateLine : AssistantAction

    /** LEFT: go back N phrases showing the English line (#347); N = presses in a 1.5 s group. */
    data object RewindEnglish : AssistantAction

    /** RIGHT: go back N phrases showing the Spanish line (#347). */
    data object RewindSpanish : AssistantAction

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
 * key is taken: OK/ENTER/PLAY_PAUSE replay, DPAD_LEFT/DPAD_RIGHT rewind N phrases showing English /
 * Spanish (#347; also with the overlay closed while [assistantAvailable], else they seek),
 * DPAD_UP explains (#347; closed, UP still opens the tracks panel), BACK/DPAD_DOWN/CAPTIONS dismiss, and the rest is [AssistantAction.Consumed], so no transport action can run behind the overlay. The exception is
 * the volume keys (VOLUME_UP/DOWN/MUTE, #178): they are null in both modes so the system still
 * changes the TV volume while the overlay is open.
 */
object AssistantKeyMapper {
    fun map(
        keyCode: Int,
        overlayOpen: Boolean,
        assistantAvailable: Boolean = true,
    ): AssistantAction? =
        if (overlayOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                -> AssistantAction.ReplayFragment

                KeyEvent.KEYCODE_DPAD_LEFT -> AssistantAction.RewindEnglish

                KeyEvent.KEYCODE_DPAD_RIGHT -> AssistantAction.RewindSpanish

                KeyEvent.KEYCODE_DPAD_UP -> AssistantAction.ExplainLine

                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_CAPTIONS,
                -> AssistantAction.DismissOverlay

                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE,
                -> null

                else -> AssistantAction.Consumed
            }
        } else {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_CAPTIONS,
                -> AssistantAction.CaptureLine

                // Without an assistant LEFT/RIGHT stay the 10 s seek of RemoteKeyMapper.
                KeyEvent.KEYCODE_DPAD_LEFT -> AssistantAction.RewindEnglish.takeIf { assistantAvailable }

                KeyEvent.KEYCODE_DPAD_RIGHT -> AssistantAction.RewindSpanish.takeIf { assistantAvailable }

                else -> null
            }
        }
}
