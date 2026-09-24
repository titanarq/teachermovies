package com.teachermovies.player.vlc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The options the libVLC adapter is built with, which are the parts of it a JVM test can reach:
 * nothing here loads `libvlc.so`, so this asserts on the exact strings `LibVLC` and `Media` get
 * (#75).
 */
class VlcMediaOptionsTest {
    // -- LibVLC options ------------------------------------------------------------------------

    @Test
    fun keepsEveryFrameInsteadOfDroppingTheLateOnes() {
        assertEquals(
            listOf("--no-drop-late-frames", "--no-skip-frames", "--audio-time-stretch"),
            VlcMediaOptions.LIB_VLC,
        )
    }

    // -- :start-time ---------------------------------------------------------------------------

    @Test
    fun expressesTheStartPositionInSecondsTheWayLibvlcParsesIt() {
        assertEquals(":start-time=90.0", VlcMediaOptions.startTime(90_000))
    }

    @Test
    fun keepsTheSubSecondPartOfTheStartPosition() {
        assertEquals(":start-time=1.234", VlcMediaOptions.startTime(1_234))
    }

    @Test
    fun asksForNoOptionWhenPlaybackStartsAtTheBeginning() {
        assertNull(VlcMediaOptions.startTime(0))
    }

    @Test
    fun asksForNoOptionForAPositionBeforeTheBeginning() {
        assertNull(VlcMediaOptions.startTime(-1_000))
    }
}
