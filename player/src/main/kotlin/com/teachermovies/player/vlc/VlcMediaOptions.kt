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
     * A fresh, mutable copy of [LIB_VLC] for one `LibVLC(context, options)` call. libVLC 3.x
     * appends its own options to the list it is given (`LibVLC.java:76`), so a read-only list makes
     * the constructor throw `UnsupportedOperationException` and the first playback crash (#217); a
     * new copy each call also keeps libVLC's additions out of [LIB_VLC].
     */
    fun libVlcOptions(): ArrayList<String> = ArrayList(LIB_VLC)

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

    /**
     * Every option the `Media` for one [VlcPlayer.open] gets, in a stable order: [startTime] when
     * [startPositionMs] is past the beginning, then -- for a [growing] file, one the torrent engine
     * is still writing -- `:file-caching=<fileCachingMs>`, a larger read cache so a read that
     * reaches bytes not yet downloaded waits instead of hitting what looks like the end of the file,
     * and `:no-input-fast-seek`, which makes a seek decode its way to the exact position rather than
     * jump to a keyframe index a partial file may not have yet.
     */
    fun forMedia(
        startPositionMs: Long,
        growing: Boolean,
        fileCachingMs: Int,
    ): List<String> =
        buildList {
            startTime(startPositionMs)?.let(::add)
            if (growing) {
                add(":file-caching=$fileCachingMs")
                add(":no-input-fast-seek")
            }
        }

    private const val MILLIS_IN_A_SECOND = 1000.0
}
