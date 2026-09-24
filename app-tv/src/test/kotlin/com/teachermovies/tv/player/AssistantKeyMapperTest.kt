package com.teachermovies.tv.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantKeyMapperTest {
    /** Keys the assistant gives no meaning of their own (transport keys and a few others). */
    private val otherKeys =
        listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_A,
        )

    /** Volume keys: never the assistant's, so the system changes the TV volume (#178). */
    private val volumeKeys =
        listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)

    @Test
    fun withTheOverlayClosedDownAndCaptionsCaptureTheLine() {
        assertEquals(AssistantAction.CaptureLine, AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_DOWN, overlayOpen = false))
        assertEquals(AssistantAction.CaptureLine, AssistantKeyMapper.map(KeyEvent.KEYCODE_CAPTIONS, overlayOpen = false))
    }

    @Test
    fun withTheOverlayClosedEveryOtherKeyFallsThroughToTheTransportMapping() {
        val fallThrough =
            otherKeys +
                listOf(
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                )
        fallThrough.forEach { keyCode ->
            assertNull("keyCode $keyCode", AssistantKeyMapper.map(keyCode, overlayOpen = false))
        }
    }

    @Test
    fun withTheOverlayOpenOkEnterAndPlayPauseReplayTheFragment() {
        listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE).forEach { keyCode ->
            assertEquals("keyCode $keyCode", AssistantAction.ReplayFragment, AssistantKeyMapper.map(keyCode, overlayOpen = true))
        }
    }

    @Test
    fun withTheOverlayOpenBackDownAndCaptionsDismissIt() {
        listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CAPTIONS).forEach { keyCode ->
            assertEquals("keyCode $keyCode", AssistantAction.DismissOverlay, AssistantKeyMapper.map(keyCode, overlayOpen = true))
        }
    }

    @Test
    fun withTheOverlayOpenEveryOtherKeyIsConsumed() {
        otherKeys.forEach { keyCode ->
            assertEquals("keyCode $keyCode", AssistantAction.Consumed, AssistantKeyMapper.map(keyCode, overlayOpen = true))
        }
    }

    @Test
    fun withTheOverlayOpenRightSpeaksTheLineAndLeftTranslatesIt() {
        assertEquals(AssistantAction.SpeakOriginal, AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_RIGHT, overlayOpen = true))
        assertEquals(AssistantAction.TranslateLine, AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_LEFT, overlayOpen = true))
    }

    @Test
    fun withTheOverlayClosedLeftAndRightStayWithTheTransportMapping() {
        assertNull(AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_RIGHT, overlayOpen = false))
        assertNull(AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_LEFT, overlayOpen = false))
    }

    @Test
    fun withTheOverlayOpenVolumeKeysPassThrough() {
        volumeKeys.forEach { keyCode ->
            assertNull("keyCode $keyCode", AssistantKeyMapper.map(keyCode, overlayOpen = true))
        }
    }

    @Test
    fun withTheOverlayClosedVolumeKeysPassThrough() {
        volumeKeys.forEach { keyCode ->
            assertNull("keyCode $keyCode", AssistantKeyMapper.map(keyCode, overlayOpen = false))
        }
    }
}
