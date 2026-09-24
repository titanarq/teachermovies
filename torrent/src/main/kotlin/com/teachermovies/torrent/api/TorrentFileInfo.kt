package com.teachermovies.torrent.api

/**
 * One file inside a torrent, as the engine reports it.
 *
 * [index] is the file's position in the torrent's own file list, which is how [TorrentEngine]
 * addresses it in [TorrentEngine.setFilePriorities] and [TorrentEngine.prioritizeWindow]; [path] is
 * its path relative to the torrent root.
 */
data class TorrentFileInfo(
    val index: Int,
    val path: String,
    val sizeBytes: Long,
    val priority: FilePriority,
    val downloadedBytes: Long,
)
