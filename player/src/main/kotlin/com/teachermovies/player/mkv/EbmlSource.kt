package com.teachermovies.player.mkv

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** The file ends before an element it declares does: a torrent still downloading, or a cut file. */
internal class TruncatedException : IOException("truncated file")

/** Bytes that cannot be EBML: a zero-length vint, an oversized element, a broken field. */
internal class CorruptException(
    message: String,
) : IOException(message)

/** One EBML element header: its [id] (marker bits kept), where its [dataStart]s and its [size]. */
internal data class EbmlHeader(
    val id: Long,
    val dataStart: Long,
    /** Payload size in bytes, or [UNKNOWN_SIZE] for an element whose size is "unknown". */
    val size: Long,
) {
    val isUnknownSize: Boolean get() = size == UNKNOWN_SIZE

    /** First byte after the element; only meaningful for a known [size]. */
    val end: Long get() = dataStart + size

    companion object {
        const val UNKNOWN_SIZE = -1L
    }
}

/**
 * Forward reader over a Matroska file that reads element headers through a small buffer and jumps
 * over payloads with [seek], so skipping a video block costs a position change and no read.
 *
 * It reads through a [FileChannel], which throws `ClosedByInterruptException` when its thread is
 * interrupted -- that is what lets a coroutine timeout stop a long walk.
 */
internal class EbmlSource(
    private val channel: FileChannel,
) {
    val length: Long = channel.size()

    private val buffer: ByteBuffer = ByteBuffer.allocate(BUFFER_SIZE).apply { limit(0) }

    /** File offset of `buffer[0]`. */
    private var bufferStart = 0L

    val position: Long
        get() = bufferStart + buffer.position()

    fun seek(target: Long) {
        if (target >= bufferStart && target <= bufferStart + buffer.limit()) {
            buffer.position((target - bufferStart).toInt())
        } else {
            bufferStart = target
            buffer.limit(0)
        }
    }

    val atEnd: Boolean get() = position >= length

    /** The next byte, or -1 at end of file. */
    private fun readByteOrEof(): Int {
        if (!buffer.hasRemaining()) {
            val next = position
            bufferStart = next
            buffer.clear()
            var read = 0
            while (read == 0) {
                read = channel.read(buffer, next)
            }
            buffer.flip()
            if (read < 0) {
                buffer.limit(0)
                return -1
            }
        }
        return buffer.get().toInt() and 0xFF
    }

    fun readByte(): Int {
        val b = readByteOrEof()
        if (b < 0) throw TruncatedException()
        return b
    }

    fun readBytes(count: Long): ByteArray {
        if (count < 0 || count > MAX_READ) throw CorruptException("element too large")
        if (position + count > length) throw TruncatedException()
        val out = ByteArray(count.toInt())
        for (i in out.indices) {
            out[i] = readByte().toByte()
        }
        return out
    }

    /**
     * The next element header, or null when the file ends exactly here (a clean end between
     * elements). A header cut in the middle is [TruncatedException].
     */
    fun readHeader(): EbmlHeader? {
        val first = readByteOrEof()
        if (first < 0) return null
        val id = readVint(first, keepMarker = true)
        val size = readVint(readByte(), keepMarker = false)
        return EbmlHeader(id = id, dataStart = position, size = size)
    }

    /**
     * An EBML variable-length integer starting with [first]. With [keepMarker] it is an element id
     * (the length marker is part of the id); without, it is a size, and an all-ones value means
     * [EbmlHeader.UNKNOWN_SIZE].
     */
    private fun readVint(
        first: Int,
        keepMarker: Boolean,
    ): Long {
        val length = Integer.numberOfLeadingZeros(first) - (Int.SIZE_BITS - Byte.SIZE_BITS) + 1
        if (first == 0 || length > MAX_VINT_LENGTH) throw CorruptException("invalid EBML integer")
        if (keepMarker && length > MAX_ID_LENGTH) throw CorruptException("invalid EBML id")
        val markerMask = 0xFF ushr length
        var value = (if (keepMarker) first else first and markerMask).toLong()
        var allOnes = (first and markerMask) == markerMask
        repeat(length - 1) {
            val b = readByte()
            if (b != 0xFF) allOnes = false
            value = (value shl Byte.SIZE_BITS) or b.toLong()
        }
        return if (!keepMarker && allOnes) EbmlHeader.UNKNOWN_SIZE else value
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_VINT_LENGTH = 8
        private const val MAX_ID_LENGTH = 4

        /** Largest single element this reader loads: far above any subtitle frame or track header. */
        const val MAX_READ = 16L * 1024 * 1024

        /** A size-style vint read out of a byte array (a Block's track number), with its length. */
        fun vintAt(
            bytes: ByteArray,
            offset: Int,
        ): Pair<Long, Int> {
            if (offset >= bytes.size) throw CorruptException("block too short")
            val first = bytes[offset].toInt() and 0xFF
            val length = Integer.numberOfLeadingZeros(first) - (Int.SIZE_BITS - Byte.SIZE_BITS) + 1
            if (first == 0 || length > MAX_VINT_LENGTH || offset + length > bytes.size) {
                throw CorruptException("invalid block header")
            }
            var value = (first and (0xFF ushr length)).toLong()
            for (i in 1 until length) {
                value = (value shl Byte.SIZE_BITS) or (bytes[offset + i].toLong() and 0xFF)
            }
            return value to length
        }

        /** Big-endian unsigned integer of 0..8 bytes, as EBML stores uint elements. */
        fun uint(bytes: ByteArray): Long {
            if (bytes.size > Long.SIZE_BYTES) throw CorruptException("integer too large")
            var value = 0L
            for (b in bytes) {
                value = (value shl Byte.SIZE_BITS) or (b.toLong() and 0xFF)
            }
            return value
        }
    }
}
