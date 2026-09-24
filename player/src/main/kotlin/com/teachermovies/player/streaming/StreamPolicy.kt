package com.teachermovies.player.streaming

/**
 * A contiguous run of bytes of a media file: [lengthBytes] bytes starting at [offsetBytes].
 *
 * Plain value, so the streaming arithmetic stays independent of `:torrent`'s piece types; the
 * caller maps it onto pieces itself.
 */
data class ByteRange(
    val offsetBytes: Long,
    val lengthBytes: Long,
)

/**
 * The byte budgets that drive stream-while-downloading.
 *
 * - [headBytes] from offset 0 and [tailBytes] ending at the end of the file must be on disk before
 *   an incomplete file is opened (containers keep their index at either end).
 * - [startBufferBytes] from the start position must also be ready before opening.
 * - [readAheadBytes] is the window prioritised ahead of the playback position while playing.
 * - Playback waits when fewer than [underrunBytes] are ready ahead of it, and only goes on again
 *   once [resumeBytes] are ready (see [decide]).
 */
data class StreamPolicy(
    val headBytes: Long,
    val tailBytes: Long,
    val startBufferBytes: Long,
    val readAheadBytes: Long,
    val underrunBytes: Long,
    val resumeBytes: Long,
) {
    init {
        require(headBytes > 0) { "headBytes must be positive, was $headBytes" }
        require(tailBytes > 0) { "tailBytes must be positive, was $tailBytes" }
        require(startBufferBytes > 0) { "startBufferBytes must be positive, was $startBufferBytes" }
        require(readAheadBytes > 0) { "readAheadBytes must be positive, was $readAheadBytes" }
        require(underrunBytes > 0) { "underrunBytes must be positive, was $underrunBytes" }
        require(resumeBytes > 0) { "resumeBytes must be positive, was $resumeBytes" }
        require(resumeBytes >= underrunBytes) {
            "resumeBytes ($resumeBytes) must not be below underrunBytes ($underrunBytes): the resume " +
                "threshold must sit above the underrun threshold or playback oscillates between " +
                "playing and waiting"
        }
    }

    companion object {
        private const val MIB = 1024L * 1024L

        /** Head 2 MiB, tail 1 MiB, start buffer 16 MiB, read-ahead 48 MiB, underrun 2 MiB, resume 12 MiB. */
        val DEFAULT =
            StreamPolicy(
                headBytes = 2 * MIB,
                tailBytes = 1 * MIB,
                startBufferBytes = 16 * MIB,
                readAheadBytes = 48 * MIB,
                underrunBytes = 2 * MIB,
                resumeBytes = 12 * MIB,
            )
    }
}
