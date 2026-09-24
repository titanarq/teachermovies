package com.teachermovies.http

import com.teachermovies.storage.DownloadLayout

/** The directory every uploaded subtitle lands under, inside a torrent's own directory. */
internal const val SUBTITLES_SUBDIR = "subs"

/**
 * Persists an externally uploaded subtitle file for a torrent (#61): `POST /api/subtitles` writes
 * through this so the player can later find the file next to the movie (#77).
 */
interface SubtitleStore {
    /**
     * Sanitises [fileName] to `[A-Za-z0-9._-]` and writes [bytes] under the torrent's own
     * `subs/` directory.
     *
     * @return [Result.success] with the absolute path written to, or [Result.failure] if
     *   [torrentId] has no known download layout, [fileName] sanitises to nothing usable (e.g.
     *   empty, `"."`, or `".."`), or the write itself fails.
     */
    fun save(torrentId: String, fileName: String, bytes: ByteArray): Result<String>
}

/**
 * [SubtitleStore] backed by [DownloadLayout]. [layoutFor] resolves a torrent id to the
 * [DownloadLayout] of the volume it lives on, or `null` when that cannot be determined (e.g. an
 * unknown torrent), in which case [save] fails.
 *
 * [fileName] is sanitised here, not by the caller: a name that arrives off the network must never
 * choose where it lands on disk (AGENTS.md "Security"). [DownloadLayout.resolveInTorrent] further
 * guards against escaping the torrent directory, but a sanitised name of `"."` or `".."` would
 * still resolve to a real directory inside it (`subs` itself, or the torrent directory), so those
 * are rejected explicitly rather than left to collide with a directory on write.
 */
class LayoutSubtitleStore(private val layoutFor: (String) -> DownloadLayout?) : SubtitleStore {
    override fun save(torrentId: String, fileName: String, bytes: ByteArray): Result<String> =
        runCatching {
            val layout = layoutFor(torrentId) ?: error("No download layout for torrent \"$torrentId\"")
            val sanitized = sanitizeSubtitleFileName(fileName)
            require(sanitized.isNotBlank() && sanitized != "." && sanitized != "..") {
                "Subtitle file name sanitised to nothing usable, was \"$fileName\""
            }
            val target = layout.resolveInTorrent(torrentId, "$SUBTITLES_SUBDIR/$sanitized")
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            target.absolutePath
        }
}

/** Keeps only `[A-Za-z0-9._-]` from [name], dropping every other character -- notably `/` and `\`. */
internal fun sanitizeSubtitleFileName(name: String): String =
    name.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }
