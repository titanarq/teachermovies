package com.teachermovies.tv.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteKeyMapperTest {
    @Test
    fun everyMappedKeyGivesItsAction() {
        val expected =
            mapOf(
                KeyEvent.KEYCODE_DPAD_CENTER to PlayerAction.TogglePlayPause,
                KeyEvent.KEYCODE_ENTER to PlayerAction.TogglePlayPause,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to PlayerAction.TogglePlayPause,
                KeyEvent.KEYCODE_SPACE to PlayerAction.TogglePlayPause,
                KeyEvent.KEYCODE_MEDIA_PLAY to PlayerAction.Play,
                KeyEvent.KEYCODE_MEDIA_PAUSE to PlayerAction.Pause,
                KeyEvent.KEYCODE_DPAD_LEFT to PlayerAction.SeekBy(-10_000L),
                KeyEvent.KEYCODE_DPAD_RIGHT to PlayerAction.SeekBy(10_000L),
                KeyEvent.KEYCODE_MEDIA_REWIND to PlayerAction.SeekBy(-30_000L),
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to PlayerAction.SeekBy(30_000L),
                KeyEvent.KEYCODE_DPAD_UP to PlayerAction.ShowTracks,
                KeyEvent.KEYCODE_MENU to PlayerAction.ShowTracks,
                KeyEvent.KEYCODE_BACK to PlayerAction.Exit,
            )

        expected.forEach { (keyCode, action) ->
            assertEquals("keyCode $keyCode", action, RemoteKeyMapper.map(keyCode))
        }
    }

    @Test
    fun otherKeysMapToNull() {
        listOf(
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_UNKNOWN,
        ).forEach { assertNull("keyCode $it", RemoteKeyMapper.map(it)) }
    }
}
