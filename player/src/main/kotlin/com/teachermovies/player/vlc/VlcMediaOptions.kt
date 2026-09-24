package com.teachermovies.player.vlc

/**
 * The libVLC options this adapter runs with, held apart from [VlcPlayer] because they are the one
 * part of it that is pure data: a JVM test can assert on them without loading the native library,
 * which nothing under `src/test` may do (#75).
 */
internal object VlcMediaOptions {
    /**
     * Handed to `LibVLC` when it is created.
     *
     * `--no-drop-late-frames` and `--no-skip-frames` keep every decoded frame instead of dropping
     * the ones that arrive late, which is what the assistant's "what did they say?" replay needs --
     * a dropped frame is a lost cue. `--audio-time-stretch` keeps the pitch when playback speed
     * changes, so a slowed-down line stays intelligible.
     */
    val LIB_VLC: List<String> =
        listOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--audio-time-stretch",
        )

    /**
     * The `Media` option that starts playback [startPositionMs] into the file, or null when it
     * starts at the beginning. libVLC's `:start-time` is in seconds, and passing `:start-time=0.0`
     * would only ask for a seek that is already where playback begins.
     */
    fun startTime(startPositionMs: Long): String? =
        if (startPositionMs > 0) {
            ":start-time=${startPositionMs / MILLIS_IN_A_SECOND}"
        } else {
            null
        }

    private const val MILLIS_IN_A_SECOND = 1000.0
}
