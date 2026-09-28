package com.teachermovies.bridge.logs

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * How far the mirror has copied (#272): the TV boot [bootId] and the last [seq] of it written to
 * a file. `since = seq` is where a restarted bridge picks the stream up, and a different `bootId`
 * on the stream means the TV restarted and [seq] no longer applies.
 */
@Serializable
data class MirrorCursor(
    val bootId: String,
    val seq: Long,
)

/**
 * [MirrorCursor] on disk, as `cursor.json` in the logs directory, next to the files it describes.
 * A save writes a `0600` temporary file and moves it over the old one atomically, so a bridge
 * stopped mid-save leaves the previous cursor rather than half a file.
 */
class MirrorCursorStore(
    val file: Path,
) {
    /** The stored cursor; null when there is none yet or it cannot be read, i.e. copy the whole buffer. */
    fun load(): MirrorCursor? =
        try {
            JSON.decodeFromString(MirrorCursor.serializer(), Files.readString(file, StandardCharsets.UTF_8))
        } catch (_: NoSuchFileException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    /** Replaces the stored cursor with [cursor]; an [IOException] reaches the caller. */
    fun save(cursor: MirrorCursor) {
        val dir = file.toAbsolutePath().parent
        Files.createDirectories(dir)
        val temp = Files.createTempFile(dir, ".cursor", ".tmp", PRIVATE)
        try {
            Files.writeString(temp, JSON.encodeToString(MirrorCursor.serializer(), cursor), StandardCharsets.UTF_8)
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object {
        const val FILE_NAME = "cursor.json"

        private val JSON = Json { ignoreUnknownKeys = true }
        private val PRIVATE = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    }
}
