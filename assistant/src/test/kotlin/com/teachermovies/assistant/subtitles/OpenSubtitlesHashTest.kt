package com.teachermovies.assistant.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class OpenSubtitlesHashTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private companion object {
        const val BLOCK_BYTES = 64 * 1024
        const val MIN_BYTES = 2 * BLOCK_BYTES
        const val HEX_16 = "^[0-9a-f]{16}$"

        /** `breakdance.avi`, the test file OpenSubtitles publishes on its `HashSourceCodes` page. */
        const val PUBLISHED_SIZE_BYTES = 12_909_756L
        const val PUBLISHED_HASH = "8e245d9679d31e12"

        /** That file's first and last 64 KiB -- every byte the algorithm ever reads. */
        const val PUBLISHED_BLOCKS = "/opensubtitles/breakdance-blocks.bin"
    }

    /** A deterministic byte pattern; only used where two hashes are compared with each other. */
    private fun pattern(index: Int): Byte = ((index * 31 + 7) and 0xFF).toByte()

    private var movies = 0

    /** Writes a new file every time, so a test can hold several movies side by side. */
    private fun writeMovie(bytes: ByteArray): File {
        val file = File(tempFolder.root, "movie-${movies++}.mkv")
        file.writeBytes(bytes)
        return file
    }

    private fun movie(
        size: Int,
        byteAt: (Int) -> Byte = { 0 },
    ): File = writeMovie(ByteArray(size) { byteAt(it) })

    private fun publishedBlocks(): ByteArray {
        val stream = javaClass.getResourceAsStream(PUBLISHED_BLOCKS)
        requireNotNull(stream) { "missing test fixture $PUBLISHED_BLOCKS" }
        return stream.use { it.readBytes() }
    }

    /**
     * Rebuilds OpenSubtitles' published test file, `breakdance.avi`: a file of the published size
     * carrying its published first and last 64 KiB, with a hole in between -- the one part of the
     * file the algorithm never reads. The fixture holds those two blocks instead of the 12 MB movie
     * (see the `README.md` next to it), so the published vector stays reproducible offline.
     */
    private fun publishedMovie(): File {
        val blocks = publishedBlocks()
        val file = File(tempFolder.root, "breakdance.avi")
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(PUBLISHED_SIZE_BYTES)
            raf.seek(0)
            raf.write(blocks, 0, BLOCK_BYTES)
            raf.seek(PUBLISHED_SIZE_BYTES - BLOCK_BYTES)
            raf.write(blocks, BLOCK_BYTES, BLOCK_BYTES)
        }
        return file
    }

    private fun hashOf(file: File): String {
        val result = OpenSubtitlesHash.of(file)
        check(result is MovieHashResult.Computed) { "expected Computed, got $result" }
        return result.hash
    }

    @Test
    fun `matches the vector OpenSubtitles publishes for its own test file`() {
        assertEquals(PUBLISHED_HASH, hashOf(publishedMovie()))
    }

    @Test
    fun `a file of zeroes hashes to its own size`() {
        assertEquals("0000000000020000", hashOf(movie(MIN_BYTES)))
    }

    @Test
    fun `formats a sum whose high bit is set as an unsigned 16-digit number`() {
        // A single word with only its high bit set contributes -2^63 to the sum.
        val highBitOnly = movie(MIN_BYTES) { index -> if (index == 7) 0x80.toByte() else 0.toByte() }

        assertEquals("8000000000020000", hashOf(highBitOnly))
    }

    @Test
    fun `reads both blocks as little-endian 64-bit words`() {
        // Byte 0 of a block is the low byte of its first word, byte 7 the high one; the tail block
        // starts at size - 64 KiB, so bits set there prove the offset too.
        val marked =
            movie(MIN_BYTES) { index ->
                when (index) {
                    0, 7, BLOCK_BYTES, BLOCK_BYTES + 7 -> 1
                    else -> 0
                }
            }

        // 2 * 2^56 (bytes 7 and 65543) + 2 (bytes 0 and 65536) + 131072 (the size).
        assertEquals("0200000000020002", hashOf(marked))
    }

    @Test
    fun `sums every word of both blocks`() {
        // Every word of an all-ones file is -1: 8192 per block, two blocks, so the hash is the size
        // minus 16384.
        assertEquals("000000000001c000", hashOf(movie(MIN_BYTES) { 0xFF.toByte() }))
    }

    @Test
    fun `ignores the bytes between the two blocks`() {
        val untouched = movie(300_000, ::pattern)
        val touchedInTheMiddle =
            movie(300_000) { index -> if (index == 150_000) 0x42 else pattern(index) }

        assertEquals(hashOf(untouched), hashOf(touchedInTheMiddle))
    }

    @Test
    fun `changes when a byte of either block changes`() {
        val untouched = movie(300_000, ::pattern)
        val firstByte = movie(300_000) { index -> if (index == 0) 0x42 else pattern(index) }
        val lastByte = movie(300_000) { index -> if (index == 299_999) 0x42 else pattern(index) }

        assertNotEquals(hashOf(untouched), hashOf(firstByte))
        assertNotEquals(hashOf(untouched), hashOf(lastByte))
    }

    @Test
    fun `returns 16 lower-case hex digits`() {
        assertTrue(hashOf(movie(300_000, ::pattern)).matches(Regex(HEX_16)))
    }

    @Test
    fun `is deterministic`() {
        assertEquals(hashOf(movie(200_000, ::pattern)), hashOf(movie(200_000, ::pattern)))
    }

    @Test
    fun `reports the file size with the hash`() {
        val result = OpenSubtitlesHash.of(movie(300_000, ::pattern))

        assertEquals(300_000L, (result as MovieHashResult.Computed).fileSizeBytes)
    }

    @Test
    fun `hashes a file of exactly 128 KiB`() {
        assertTrue(OpenSubtitlesHash.of(movie(MIN_BYTES)) is MovieHashResult.Computed)
    }

    @Test
    fun `rejects a file one byte short of 128 KiB`() {
        assertEquals(MovieHashResult.TooSmall((MIN_BYTES - 1).toLong()), OpenSubtitlesHash.of(movie(MIN_BYTES - 1)))
    }

    @Test
    fun `rejects an empty file`() {
        assertEquals(MovieHashResult.TooSmall(0L), OpenSubtitlesHash.of(movie(0)))
    }

    @Test
    fun `reports a file that is not there instead of throwing`() {
        val result = OpenSubtitlesHash.of(File(tempFolder.root, "missing.mkv"))

        assertTrue("expected Unreadable, got $result", result is MovieHashResult.Unreadable)
    }

    @Test
    fun `reports a directory instead of throwing`() {
        val directory = File(tempFolder.root, "movie.mkv").apply { mkdirs() }

        assertTrue(OpenSubtitlesHash.of(directory) is MovieHashResult.Unreadable)
    }
}
