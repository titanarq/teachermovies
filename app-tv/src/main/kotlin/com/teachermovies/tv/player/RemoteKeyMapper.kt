package com.teachermovies.tv.player

import android.view.KeyEvent

/**
 * Pure mapping from an Android TV remote key code to a [PlayerAction] (#78). Keys that mean
 * nothing to the player map to null, so the screen lets them through (volume, HOME...).
 *
 * The `KeyEvent.KEYCODE_*` values are compile-time constants, so this runs in a plain JVM test.
 */
object RemoteKeyMapper {
    /** Short seek for DPAD_LEFT/RIGHT. */
    const val SHORT_SEEK_MS = 10_000L

    /** Long seek for MEDIA_REWIND/MEDIA_FAST_FORWARD. */
    const val LONG_SEEK_MS = 30_000L

    fun map(keyCode: Int): PlayerAction? =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_SPACE,
            -> PlayerAction.TogglePlayPause

            KeyEvent.KEYCODE_MEDIA_PLAY -> PlayerAction.Play

            KeyEvent.KEYCODE_MEDIA_PAUSE -> PlayerAction.Pause

            KeyEvent.KEYCODE_DPAD_LEFT -> PlayerAction.SeekBy(-SHORT_SEEK_MS)

            KeyEvent.KEYCODE_DPAD_RIGHT -> PlayerAction.SeekBy(SHORT_SEEK_MS)

            KeyEvent.KEYCODE_MEDIA_REWIND -> PlayerAction.SeekBy(-LONG_SEEK_MS)

            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> PlayerAction.SeekBy(LONG_SEEK_MS)

            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MENU,
            -> PlayerAction.ShowTracks

            KeyEvent.KEYCODE_BACK -> PlayerAction.Exit

            else -> null
        }
}
