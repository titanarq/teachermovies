package com.teachermovies.tv.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantKeyMapperTest {
    /** Keys the assistant gives no meaning of their own (transport keys and a few others). */
    private val otherKeys =
        listOf(
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
        assertEquals(
            AssistantAction.CaptureLine,
            AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_DOWN, overlayOpen = false),
        )
        assertEquals(
            AssistantAction.CaptureLine,
            AssistantKeyMapper.map(KeyEvent.KEYCODE_CAPTIONS, overlayOpen = false),
        )
    }

    @Test
    fun withTheOverlayClosedEveryOtherKeyFallsThroughToTheTransportMapping() {
        val fallThrough =
            otherKeys +
                listOf(
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_BACK,
                )
        fallThrough.forEach { keyCode ->
            assertNull("keyCode $keyCode", AssistantKeyMapper.map(keyCode, overlayOpen = false))
        }
    }

    @Test
    fun withTheOverlayOpenOkEnterAndPlayPauseReplayTheFragment() {
        val replayKeys = listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        replayKeys.forEach { keyCode ->
            assertEquals(
                "keyCode $keyCode",
                AssistantAction.ReplayFragment,
                AssistantKeyMapper.map(keyCode, overlayOpen = true),
            )
        }
    }

    @Test
    fun withTheOverlayOpenBackDownAndCaptionsDismissIt() {
        listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CAPTIONS).forEach { keyCode ->
            assertEquals(
                "keyCode $keyCode",
                AssistantAction.DismissOverlay,
                AssistantKeyMapper.map(keyCode, overlayOpen = true),
            )
        }
    }

    @Test
    fun withTheOverlayOpenUpExplainsAndWithItClosedFallsThroughToOpenTheTracksPanel() {
        assertEquals(AssistantAction.ExplainLine, AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_UP, overlayOpen = true))
        assertNull(AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_UP, overlayOpen = false))
        assertEquals(PlayerAction.ShowTracks, RemoteKeyMapper.map(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test
    fun withTheOverlayOpenEveryOtherKeyIsConsumed() {
        otherKeys.forEach { keyCode ->
            assertEquals(
                "keyCode $keyCode",
                AssistantAction.Consumed,
                AssistantKeyMapper.map(keyCode, overlayOpen = true),
            )
        }
    }

    @Test
    fun leftAndRightRewindEnglishAndSpanishWithTheOverlayOpenOrClosed() {
        listOf(true, false).forEach { open ->
            assertEquals(
                AssistantAction.RewindEnglish,
                AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_LEFT, overlayOpen = open),
            )
            assertEquals(
                AssistantAction.RewindSpanish,
                AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_RIGHT, overlayOpen = open),
            )
        }
    }

    @Test
    fun withTheOverlayClosedLeftAndRightAreAlwaysTheAssistants() {
        // The view model decides between rewind, ignore and a told 10 s seek (#374).
        assertEquals(
            AssistantAction.RewindEnglish,
            AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_LEFT, overlayOpen = false),
        )
        assertEquals(
            AssistantAction.RewindSpanish,
            AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_RIGHT, overlayOpen = false),
        )
    }

    @Test
    fun rightNoLongerExplains() {
        assertEquals(
            AssistantAction.RewindSpanish,
            AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_RIGHT, overlayOpen = true),
        )
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
