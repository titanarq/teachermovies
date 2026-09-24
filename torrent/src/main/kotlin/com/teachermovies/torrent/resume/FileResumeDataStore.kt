package com.teachermovies.torrent.resume

import com.teachermovies.core.model.TorrentId
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A [ResumeDataStore] keeping one `<dir>/<infohash>.fastresume` file per torrent.
 *
 * [save] writes `<infohash>.fastresume.tmp` first and then renames it over the real file, so a
 * crash or power cut mid-write leaves either the old or the new copy, never a truncated one.
 * [load] reads only files named `<40 or 64 lower-case hex>.fastresume` (leftover `.tmp` files and
 * anything else are ignored); a file that cannot be read or is not one well-formed bencoded
 * dictionary is skipped and reported, never thrown.
 *
 * @param dir the directory holding the files; created on first [save].
 */
class FileResumeDataStore(
    private val dir: File,
) : ResumeDataStore {
    /** @throws IOException when the file cannot be written. */
    override fun save(
        id: TorrentId,
        bytes: ByteArray,
    ) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val target = fileOf(id)
        val tmp = File(dir, target.name + TMP_SUFFIX)
        tmp.writeBytes(bytes)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun load(): ResumeDataLoad {
        val files = dir.listFiles() ?: return ResumeDataLoad(emptyMap(), emptyList())
        val entries = LinkedHashMap<TorrentId, ByteArray>()
        val skipped = ArrayList<SkippedResumeData>()
        for (file in files.sortedBy { it.name }) {
            val match = NAME_PATTERN.matchEntire(file.name) ?: continue
            if (!file.isFile) continue
            val bytes =
                try {
                    file.readBytes()
                } catch (e: IOException) {
                    skipped += SkippedResumeData(file.name, "unreadable: ${e.message ?: e.javaClass.name}")
                    continue
                }
            if (!Bencode.isDictionary(bytes)) {
                skipped += SkippedResumeData(file.name, "not a bencoded dictionary")
                continue
            }
            entries[TorrentId(match.groupValues[1])] = bytes
        }
        return ResumeDataLoad(entries, skipped)
    }

    /** @throws IOException when an existing file cannot be deleted. */
    override fun delete(id: TorrentId) {
        val file = fileOf(id)
        if (file.exists() && !file.delete()) throw IOException("cannot delete $file")
    }

    private fun fileOf(id: TorrentId): File = File(dir, id.value + SUFFIX)

    private companion object {
        const val SUFFIX = ".fastresume"
        const val TMP_SUFFIX = ".tmp"
        val NAME_PATTERN = Regex("""([0-9a-f]{40}|[0-9a-f]{64})\.fastresume""")
    }
}

/** A structural bencode check: no values are decoded, only their shape is verified. */
internal object Bencode {
    /** True when [bytes] is exactly one well-formed bencoded dictionary, with nothing after it. */
    fun isDictionary(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes[0] != 'd'.code.toByte()) return false
        return skipValue(bytes, 0, depth = 0) == bytes.size
    }

    /** The index just after the value starting at [start], or -1 when it is malformed. */
    private fun skipValue(
        bytes: ByteArray,
        start: Int,
        depth: Int,
    ): Int {
        if (start >= bytes.size || depth > MAX_DEPTH) return -1
        return when (val c = bytes[start].toInt().toChar()) {
            'i' -> {
                val end = indexOf(bytes, 'e', start + 1)
                if (end < 0 || !INTEGER.matches(String(bytes, start + 1, end - start - 1, Charsets.US_ASCII))) -1 else end + 1
            }
            'l', 'd' -> {
                var i = start + 1
                var isKey = true
                while (i < bytes.size && bytes[i] != 'e'.code.toByte()) {
                    if (c == 'd' && isKey && bytes[i].toInt().toChar() !in '0'..'9') return -1
                    i = skipValue(bytes, i, depth + 1)
                    if (i < 0) return -1
                    isKey = !isKey
                }
                if (i >= bytes.size || (c == 'd' && !isKey)) -1 else i + 1
            }
            in '0'..'9' -> {
                val colon = indexOf(bytes, ':', start)
                if (colon < 0) return -1
                val length = String(bytes, start, colon - start, Charsets.US_ASCII).toLongOrNull() ?: return -1
                val end = colon + 1 + length
                if (end > bytes.size) -1 else end.toInt()
            }
            else -> -1
        }
    }

    private fun indexOf(
        bytes: ByteArray,
        char: Char,
        from: Int,
    ): Int {
        for (i in from until bytes.size) if (bytes[i] == char.code.toByte()) return i
        return -1
    }

    private const val MAX_DEPTH = 64
    private val INTEGER = Regex("""0|-?[1-9][0-9]*""")
}
