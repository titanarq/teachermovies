package com.teachermovies.assistant.subtitles

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Outcome of [OpenSubtitlesHash.of]. */
sealed interface MovieHashResult {
    /** [hash] is the moviehash of a [fileSizeBytes]-byte file: 16 lower-case hex digits. */
    data class Computed(
        val hash: String,
        val fileSizeBytes: Long,
    ) : MovieHashResult

    /** The file is shorter than the two blocks the algorithm reads, so it has no moviehash. */
    data class TooSmall(
        val fileSizeBytes: Long,
    ) : MovieHashResult

    /** The file could not be read; [reason] names the failure. */
    data class Unreadable(
        val reason: String,
    ) : MovieHashResult
}

/**
 * The OpenSubtitles moviehash of a completed movie: its size in bytes plus the sums of its first and
 * last 64 KiB read as little-endian 64-bit words, rendered as 16 lower-case hex digits (ADR-0005 §5
 * -- the TV hashes each movie it stores so the laptop bridge can search OpenSubtitles by hash).
 *
 * Only the two ends of the file are touched, so hashing a multi-gigabyte movie costs 128 KiB of I/O.
 * No exception escapes [of]: a file that is not there, or cannot be read, becomes
 * [MovieHashResult.Unreadable].
 */
object OpenSubtitlesHash {
    /** Bytes read from each end of the file; the published algorithm fixes this block size. */
    private const val BLOCK_BYTES = 64 * 1024

    /** Shorter files have no moviehash: their two blocks would overlap, and OpenSubtitles skips them. */
    private const val MIN_BYTES = 2L * BLOCK_BYTES

    fun of(file: File): MovieHashResult {
        if (!file.isFile) return MovieHashResult.Unreadable("no such file: ${file.name}")

        val size = file.length()
        if (size < MIN_BYTES) return MovieHashResult.TooSmall(size)

        return try {
            MovieHashResult.Computed(hashOf(file, size), size)
        } catch (e: Exception) {
            MovieHashResult.Unreadable(e.message ?: e::class.java.simpleName)
        }
    }

    private fun hashOf(
        file: File,
        size: Long,
    ): String {
        var sum = size
        RandomAccessFile(file, "r").use { raf ->
            val block = ByteArray(BLOCK_BYTES)
            sum += blockSum(raf, block, position = 0L)
            sum += blockSum(raf, block, position = size - BLOCK_BYTES)
        }
        return String.format(Locale.ROOT, "%016x", sum)
    }

    /** The little-endian 64-bit sum of the [BLOCK_BYTES] bytes starting at [position]. */
    private fun blockSum(
        raf: RandomAccessFile,
        block: ByteArray,
        position: Long,
    ): Long {
        raf.seek(position)
        raf.readFully(block)
        val words = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0L
        while (words.hasRemaining()) sum += words.long
        return sum
    }
}
