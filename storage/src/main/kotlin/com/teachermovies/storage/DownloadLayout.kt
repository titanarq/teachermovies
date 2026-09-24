package com.teachermovies.storage

import java.io.File

private const val MOVIES_DIR_NAME = "Movies"

// A directory name, never a path: separators and `..` are outside the set, so an id cannot name
// anything but its own directory.
private val TORRENT_ID_PATTERN = Regex("[0-9A-Za-z_-]+")

/**
 * Where one volume keeps this app's downloads: `<volumeRoot>/Movies/<torrentId>/` (AGENTS.md
 * "Persistence").
 *
 * The layout is pure path arithmetic over a root the caller supplies -- volume enumeration and the
 * persisted choice of volume are #43 -- so it runs on the JVM with no Android runtime. Storage
 * holds no torrent knowledge beyond the id string and paths; what a torrent is belongs to :torrent.
 */
class DownloadLayout(
    private val volumeRoot: File,
) {
    /** `<volumeRoot>/Movies`, whether or not it exists yet. */
    fun moviesDir(): File = File(volumeRoot, MOVIES_DIR_NAME)

    /**
     * `<volumeRoot>/Movies/<torrentId>`, whether or not it exists yet.
     *
     * @throws IllegalArgumentException if [torrentId] is blank or holds anything outside
     *   `[0-9A-Za-z_-]`: a separator or a `..` inside an id would name a directory that is not this
     *   torrent's own.
     */
    fun torrentDir(torrentId: String): File {
        requireValidTorrentId(torrentId)
        return File(moviesDir(), torrentId)
    }

    /**
     * Creates [torrentDir] and its parents and returns it; a directory that already exists is a
     * success.
     *
     * Never throws. An unusable id, a filesystem error, or a file already sitting where the
     * directory belongs all come back as [Result.failure] carrying the cause: a volume that is
     * removed or full is a normal state to report, not a crash.
     */
    fun ensureTorrentDir(torrentId: String): Result<File> =
        runCatching {
            val dir = torrentDir(torrentId)
            // mkdirs() also returns false when another writer created it first, hence the re-check.
            check(dir.isDirectory || dir.mkdirs() || dir.isDirectory) { "Could not create ${dir.path}" }
            dir
        }

    /**
     * Resolves [relativePath] -- a name inside a torrent, as the torrent itself reports it -- inside
     * [torrentDir].
     *
     * @throws IllegalArgumentException if [torrentId] is invalid, if [relativePath] is blank or
     *   absolute, or if it still points outside the torrent directory once `..` segments are
     *   collapsed. A name that arrives off the network never chooses where it lands on disk.
     */
    fun resolveInTorrent(
        torrentId: String,
        relativePath: String,
    ): File {
        require(relativePath.isNotBlank()) { "Relative path must not be blank" }
        require(!relativePath.startsWith("/") && !relativePath.startsWith("\\")) {
            "Relative path must not be absolute, was \"$relativePath\""
        }
        val torrentPath = torrentDir(torrentId).toPath().normalize()
        val resolved = torrentPath.resolve(relativePath).normalize()
        require(resolved.startsWith(torrentPath)) {
            "Relative path escapes the torrent directory, was \"$relativePath\""
        }
        return resolved.toFile()
    }

    private fun requireValidTorrentId(torrentId: String) {
        require(torrentId.isNotBlank()) { "Torrent id must not be blank" }
        require(TORRENT_ID_PATTERN.matches(torrentId)) {
            "Torrent id must hold only [0-9A-Za-z_-] -- no path separator, no '..' -- was \"$torrentId\""
        }
    }
}
