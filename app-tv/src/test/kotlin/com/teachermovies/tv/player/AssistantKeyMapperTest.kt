package com.teachermovies.tv.player

import android.view.KeyEvent
import com.teachermovies.core.settings.PlayerMode
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
    fun withTheOverlayClosedUpAndCaptionsOpenTheMenu() {
        assertEquals(
            AssistantAction.CaptureLine,
            AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_UP, overlayOpen = false),
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
                    KeyEvent.KEYCODE_DPAD_RIGHT,
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
    fun withTheOverlayOpenBackAndCaptionsDismissIt() {
        listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_CAPTIONS).forEach { keyCode ->
            assertEquals(
                "keyCode $keyCode",
                AssistantAction.DismissOverlay,
                AssistantKeyMapper.map(keyCode, overlayOpen = true),
            )
        }
    }

    @Test
    fun withTheOverlayOpenUpExplainsAndWithItClosedOpensTheMenu() {
        assertEquals(AssistantAction.ExplainLine, AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_UP, overlayOpen = true))
        assertEquals(AssistantAction.CaptureLine, AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_UP, overlayOpen = false))
    }

    @Test
    fun menuOpensTheTracksPanelAndIsNeverTheAssistants() {
        assertNull(AssistantKeyMapper.map(KeyEvent.KEYCODE_MENU, overlayOpen = false))
        assertEquals(PlayerAction.ShowTracks, RemoteKeyMapper.map(KeyEvent.KEYCODE_MENU))
    }

    @Test
    fun rightClosedIsNotTakenByTheAssistantAndIsTheNativeSeek() {
        assertNull(AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_RIGHT, overlayOpen = false))
        assertEquals(PlayerAction.SeekBy(10_000L), RemoteKeyMapper.map(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun rightWithTheOverlayOpenIsConsumedSoNothingSeeksBehindIt() {
        assertEquals(AssistantAction.Consumed, AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_RIGHT, overlayOpen = true))
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
    fun leftAndDownRewindEnglishAndSpanishWithTheOverlayOpenOrClosed() {
        listOf(true, false).forEach { open ->
            assertEquals(
                AssistantAction.RewindEnglish,
                AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_LEFT, overlayOpen = open),
            )
            assertEquals(
                AssistantAction.RewindSpanish,
                AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_DOWN, overlayOpen = open),
            )
        }
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

    @Test
    fun inNormalModeTheMenuClosedMapsNothingSoTheTransportMappingApplies() {
        val keys =
            otherKeys +
                listOf(
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_CAPTIONS,
                )
        keys.forEach { keyCode ->
            assertNull(
                "keyCode $keyCode",
                AssistantKeyMapper.map(keyCode, overlayOpen = false, mode = PlayerMode.NORMAL),
            )
        }
        assertEquals(PlayerAction.SeekBy(-10_000L), RemoteKeyMapper.map(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(PlayerAction.SeekBy(10_000L), RemoteKeyMapper.map(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertNull(RemoteKeyMapper.map(KeyEvent.KEYCODE_DPAD_UP))
        assertNull(RemoteKeyMapper.map(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(PlayerAction.ShowTracks, RemoteKeyMapper.map(KeyEvent.KEYCODE_MENU))
    }

    @Test
    fun inTeacherModeTheMenuClosedMappingIsTheDefault() {
        assertEquals(
            AssistantAction.RewindEnglish,
            AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_LEFT, overlayOpen = false, mode = PlayerMode.TEACHER),
        )
        assertEquals(
            AssistantAction.RewindSpanish,
            AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_DOWN, overlayOpen = false, mode = PlayerMode.TEACHER),
        )
        assertEquals(
            AssistantAction.CaptureLine,
            AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_UP, overlayOpen = false, mode = PlayerMode.TEACHER),
        )
    }

    @Test
    fun anOpenMenuIsAnsweredInEitherMode() {
        PlayerMode.entries.forEach { mode ->
            assertEquals(
                AssistantAction.ExplainLine,
                AssistantKeyMapper.map(KeyEvent.KEYCODE_DPAD_UP, overlayOpen = true, mode = mode),
            )
        }
    }
}
