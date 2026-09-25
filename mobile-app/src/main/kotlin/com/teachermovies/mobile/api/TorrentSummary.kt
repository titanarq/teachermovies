package com.teachermovies.mobile.api

import kotlinx.serialization.Serializable

/**
 * One element of `GET /api/torrents`, mirrored from the TV's `TorrentDto`
 * (docs/modules/http-server.md); fields the phone does not use (`uploadSpeed`, `ratio`) are
 * ignored. [state] is snake_case (`fetching_metadata`, `downloading`, `completed`, ...),
 * [progress] is 0..100, speeds are bytes per second, [etaSeconds] is `null` when unknown.
 */
@Serializable
data class TorrentSummary(
    val id: String,
    val name: String,
    val state: String,
    val progress: Double,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val downloadSpeed: Long,
    val peers: Int,
    val etaSeconds: Long? = null,
)
