package com.teachermovies.tv.player

import android.view.KeyEvent
import com.teachermovies.core.settings.PlayerMode

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

    /** DPAD_LEFT: go back N phrases showing the English line (#347); N = presses in a 1.5 s group. */
    data object RewindEnglish : AssistantAction

    /** DPAD_DOWN: go back N phrases showing the Spanish line (#347, moved from RIGHT). */
    data object RewindSpanish : AssistantAction

    /** Close the captured-line overlay and resume the movie. */
    data object DismissOverlay : AssistantAction

    /** A key pressed while the overlay is open that means nothing to it: swallowed, no effect. */
    data object Consumed : AssistantAction
}

/**
 * Pure mapping from a remote key code to an [AssistantAction] (#86).
 *
 * Menu closed: DPAD_LEFT rewinds N phrases showing English, DPAD_DOWN the same showing Spanish
 * (#347; the view model seeks 10 s when there are no English subtitles, #374), DPAD_UP and CAPTIONS
 * open the captured-line menu; every other key is null so [RemoteKeyMapper] keeps handling it. In
 * particular DPAD_RIGHT is null: it stays the player's native forward seek (and its native
 * hold-to-seek), because it is the intuitive key for "forward"; the Spanish rewind therefore lives
 * on DOWN and the menu moved to UP. The tracks panel that UP used to open is on MENU.
 *
 * Menu open: OK/ENTER/PLAY_PAUSE replay, DPAD_LEFT / DPAD_DOWN rewind English / Spanish (DOWN no
 * longer dismisses), DPAD_UP explains, BACK/CAPTIONS dismiss, and the rest -- DPAD_RIGHT included, so
 * no seek runs behind the overlay -- is [AssistantAction.Consumed]. The exception is the volume
 * keys (VOLUME_UP/DOWN/MUTE, #178): they are null in both modes so the system still changes the TV
 * volume while the menu is open.
 *
 * [PlayerMode.NORMAL] (menu closed): every key is null, so [RemoteKeyMapper]'s plain-player mapping
 * applies (LEFT/RIGHT seek 10 s, UP/DOWN nothing). The overlay-open mapping does not depend on the
 * mode: an open menu is always answered.
 */
object AssistantKeyMapper {
    fun map(
        keyCode: Int,
        overlayOpen: Boolean,
        mode: PlayerMode = PlayerMode.TEACHER,
    ): AssistantAction? =
        if (overlayOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                -> AssistantAction.ReplayFragment

                KeyEvent.KEYCODE_DPAD_LEFT -> AssistantAction.RewindEnglish

                KeyEvent.KEYCODE_DPAD_DOWN -> AssistantAction.RewindSpanish

                KeyEvent.KEYCODE_DPAD_UP -> AssistantAction.ExplainLine

                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_CAPTIONS,
                -> AssistantAction.DismissOverlay

                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE,
                -> null

                else -> AssistantAction.Consumed
            }
        } else if (mode == PlayerMode.NORMAL) {
            null
        } else {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_CAPTIONS,
                -> AssistantAction.CaptureLine

                // Always the assistant's (#374): without hidden English subtitles the view model
                // itself seeks 10 s and says why, or ignores the key while they are starting.
                KeyEvent.KEYCODE_DPAD_LEFT -> AssistantAction.RewindEnglish

                KeyEvent.KEYCODE_DPAD_DOWN -> AssistantAction.RewindSpanish

                else -> null
            }
        }
}
