package com.teachermovies.torrent.api

/**
 * The answer of [TorrentEngine.rangeReadiness] for a byte range of one file.
 *
 * [ready] is true only when every piece covering the range is on disk. [readyBytes] is how many
 * bytes are available contiguously from the range's start (at most the requested length), so a
 * player can read that far without waiting. [missingPieces] lists the absolute indices of the
 * covering pieces that are not on disk yet, in ascending order (empty when [ready]).
 */
data class RangeReadiness(
    val ready: Boolean,
    val readyBytes: Long,
    val missingPieces: List<Int>,
)
