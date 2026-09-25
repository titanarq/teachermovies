package com.teachermovies.player.vlc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
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

    @Test
    fun handsLibvlcAMutableListItCanAppendItsOwnOptionsTo() {
        val options: MutableList<String> = VlcMediaOptions.libVlcOptions()

        options.add("-vvv") // what LibVLC(context, options) does; a read-only list throws here (#217)

        assertEquals(
            listOf("--no-drop-late-frames", "--no-skip-frames", "--audio-time-stretch", "-vvv"),
            options,
        )
    }

    @Test
    fun handsLibvlcAFreshCopyEachTimeWithTheSameOptions() {
        val first = VlcMediaOptions.libVlcOptions()
        first.add("-vvv")

        val second = VlcMediaOptions.libVlcOptions()

        assertNotSame(first, second)
        assertEquals(VlcMediaOptions.LIB_VLC, second)
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

    // -- Media options ---------------------------------------------------------------------------

    @Test
    fun aFinishedFileFromTheBeginningGetsNoMediaOption() {
        assertEquals(emptyList<String>(), VlcMediaOptions.forMedia(0, growing = false, fileCachingMs = 3000))
    }

    @Test
    fun aFinishedFileResumedGetsOnlyTheStartTime() {
        assertEquals(
            listOf(":start-time=90.0"),
            VlcMediaOptions.forMedia(90_000, growing = false, fileCachingMs = 3000),
        )
    }

    @Test
    fun aGrowingFileFromTheBeginningGetsTheCacheAndExactSeekOptions() {
        assertEquals(
            listOf(":file-caching=3000", ":no-input-fast-seek"),
            VlcMediaOptions.forMedia(0, growing = true, fileCachingMs = 3000),
        )
    }

    @Test
    fun aGrowingFileResumedGetsTheStartTimeFirstThenTheGrowingOptions() {
        assertEquals(
            listOf(":start-time=1.234", ":file-caching=5000", ":no-input-fast-seek"),
            VlcMediaOptions.forMedia(1_234, growing = true, fileCachingMs = 5000),
        )
    }
}
