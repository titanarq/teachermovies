package com.teachermovies.player.streaming

/**
 * Pure byte arithmetic for stream-while-downloading: where a playback position sits in the file,
 * which window to prioritise ahead of it, and which ranges must be on disk before an incomplete
 * file is opened.
 *
 * Positions are mapped to bytes assuming a constant bitrate. That is deliberate: an exact mapping
 * would need the container index, which `:player` does not parse, and the read-ahead window is
 * far larger than the error the assumption introduces.
 */
object StreamWindowCalculator {
    /**
     * The byte offset of [positionMs] in a file of [fileSizeBytes] lasting [durationMs], clamped
     * to `0..fileSizeBytes`. Answers `0` when [durationMs] is unknown (`<= 0`) or [positionMs] is
     * not positive.
     */
    fun byteOffsetFor(
        positionMs: Long,
        durationMs: Long,
        fileSizeBytes: Long,
    ): Long {
        if (durationMs <= 0 || positionMs <= 0 || fileSizeBytes <= 0) return 0L
        if (positionMs >= durationMs) return fileSizeBytes
        // fileSize * position / duration without overflowing: split fileSize by duration first.
        val whole = (fileSizeBytes / durationMs) * positionMs
        val remainder = (fileSizeBytes % durationMs) * positionMs / durationMs
        return (whole + remainder).coerceIn(0L, fileSizeBytes)
    }

    /**
     * The read-ahead window starting at [positionMs]'s byte offset: at most
     * [StreamPolicy.readAheadBytes] long, truncated at the end of the file, never negative.
     */
    fun windowFor(
        positionMs: Long,
        durationMs: Long,
        fileSizeBytes: Long,
        policy: StreamPolicy,
    ): ByteRange {
        val offset = byteOffsetFor(positionMs, durationMs, fileSizeBytes)
        val size = fileSizeBytes.coerceAtLeast(0L)
        val length = minOf(policy.readAheadBytes, size - offset).coerceAtLeast(0L)
        return ByteRange(offsetBytes = offset, lengthBytes = length)
    }

    /**
     * The ranges that must be ready before an incomplete file is opened: the head, the tail and
     * the start buffer at [startPositionMs]'s byte offset. Clamped to the file, in ascending
     * offset order, with overlapping or touching ranges merged so no byte is requested twice.
     * Empty for an empty file.
     */
    fun openRanges(
        fileSizeBytes: Long,
        startPositionMs: Long,
        durationMs: Long,
        policy: StreamPolicy,
    ): List<ByteRange> {
        if (fileSizeBytes <= 0) return emptyList()
        val startOffset = byteOffsetFor(startPositionMs, durationMs, fileSizeBytes)
        val startEnd = minOf(fileSizeBytes, startOffset + minOf(policy.startBufferBytes, fileSizeBytes))
        // As half-open [start, end) intervals, clamped to the file.
        val intervals =
            listOf(
                0L to minOf(policy.headBytes, fileSizeBytes),
                maxOf(0L, fileSizeBytes - policy.tailBytes) to fileSizeBytes,
                startOffset to startEnd,
            ).filter { (start, end) -> end > start }
                .sortedBy { it.first }

        val merged = mutableListOf<Pair<Long, Long>>()
        for ((start, end) in intervals) {
            val last = merged.lastOrNull()
            if (last != null && start <= last.second) {
                merged[merged.lastIndex] = last.first to maxOf(last.second, end)
            } else {
                merged += start to end
            }
        }
        return merged.map { (start, end) -> ByteRange(offsetBytes = start, lengthBytes = end - start) }
    }
}
