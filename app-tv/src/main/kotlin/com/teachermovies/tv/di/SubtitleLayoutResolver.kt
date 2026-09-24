package com.teachermovies.tv.di

import com.teachermovies.storage.DownloadLayout
import com.teachermovies.torrent.api.TorrentSnapshot
import java.io.File

/**
 * Finds the [DownloadLayout] a torrent's files live under, for `LayoutSubtitleStore` (#61, #66):
 * an uploaded subtitle goes next to the movie it belongs to, on whichever volume that is.
 *
 * The engine reports each torrent's payload directory as [TorrentSnapshot.savePath], which is
 * `<volume root>/Movies/<torrent id>` (`SavePathProviderFactory`); the volume root is read back
 * from it. Null when the torrent is unknown, has no save path yet (no metadata), or its save path
 * does not have that shape -- the upload then fails instead of guessing a volume.
 */
object SubtitleLayoutResolver {

    fun layoutFor(torrentId: String, torrents: List<TorrentSnapshot>): DownloadLayout? {
        val savePath = torrents.firstOrNull { it.id.value == torrentId }?.savePath ?: return null
        val torrentDir = File(savePath)
        if (torrentDir.name != torrentId) return null
        val moviesDir = torrentDir.parentFile ?: return null
        val volumeRoot = moviesDir.parentFile ?: return null
        val layout = DownloadLayout(volumeRoot)
        return layout.takeIf { it.moviesDir() == moviesDir }
    }
}
