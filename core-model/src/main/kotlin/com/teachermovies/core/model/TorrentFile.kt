package com.teachermovies.core.model

/**
 * One file inside a torrent.
 *
 * [index] is the file's position in the torrent's own file list, which is how jlibtorrent addresses
 * it for priority changes; [path] is its path relative to the torrent root.
 */
data class TorrentFile(
    val index: Int,
    val path: String,
    val sizeBytes: Long,
    val selected: Boolean,
)
