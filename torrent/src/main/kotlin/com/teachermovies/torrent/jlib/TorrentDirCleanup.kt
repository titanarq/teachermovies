package com.teachermovies.torrent.jlib

import com.teachermovies.core.model.TorrentId
import java.io.File

/**
 * Removes a torrent's own save directory (`<volume>/Movies/<torrent-id>/`) once libtorrent has
 * deleted its files (#229). libtorrent deletes the payload and the torrent's top folder but not the
 * save path it was given, so without this every delete leaves an empty hash-named directory behind.
 *
 * The deletion is asynchronous: [removeRequested] records the directory when a remove with
 * `deleteFiles` is issued, and [filesDeleted] (on `torrent_deleted_alert`) removes it -- only if it
 * is still an empty directory named after the torrent's id, and never recursively. A directory that
 * still holds anything (for instance a user-uploaded `subs/` file) is left alone. [deleteFailed] (on
 * `torrent_delete_failed_alert`) and a remove without `deleteFiles` forget the directory.
 *
 * Not thread-safe: the engine confines it to its serial dispatcher. A process death between the
 * remove and the alert leaves the directory in place; nothing is lost but the cleanup.
 */
internal class TorrentDirCleanup {
    private val pending = HashMap<TorrentId, File>()

    /** A remove of [id] was issued; [saveDir] is its save path, null when unknown. */
    fun removeRequested(
        id: TorrentId,
        saveDir: File?,
        deleteFiles: Boolean,
    ) {
        if (deleteFiles && saveDir != null) pending[id] = saveDir else pending.remove(id)
    }

    /** libtorrent deleted [id]'s files; returns true when its save directory was removed. */
    fun filesDeleted(id: TorrentId): Boolean {
        val dir = pending.remove(id) ?: return false
        return removeIfEmpty(dir, expectedName = id.value)
    }

    /** libtorrent could not delete [id]'s files: keep whatever is left on disk. */
    fun deleteFailed(id: TorrentId) {
        pending.remove(id)
    }

    private fun removeIfEmpty(
        dir: File,
        expectedName: String,
    ): Boolean {
        // Only the torrent's own directory, never a shared parent a custom provider may have given.
        if (dir.name != expectedName || !dir.isDirectory) return false
        val entries = dir.list() ?: return false
        // File.delete() refuses a non-empty directory too; the check keeps the intent explicit.
        return entries.isEmpty() && dir.delete()
    }
}
