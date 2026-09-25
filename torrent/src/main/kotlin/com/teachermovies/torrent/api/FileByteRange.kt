package com.teachermovies.torrent.api

/**
 * `[offsetBytes, offsetBytes + lengthBytes)` of one file of a torrent, as
 * [TorrentEngine.prioritizeRanges] takes it.
 */
data class FileByteRange(
    val offsetBytes: Long,
    val lengthBytes: Long,
)
