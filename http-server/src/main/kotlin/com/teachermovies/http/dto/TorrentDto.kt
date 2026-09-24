package com.teachermovies.http.dto

import com.teachermovies.core.model.DownloadState
import com.teachermovies.torrent.api.TorrentSnapshot
import kotlinx.serialization.Serializable
import kotlin.math.round

/**
 * One torrent's live state over the wire (`GET /api/torrents`, `GET /api/torrents/{id}`;
 * docs/VISION.md "API local"). [state] is [DownloadState] in snake_case; [progress] is a percentage
 * with one decimal (`17.4`, not `0.174`); [ratio] is uploaded bytes over downloaded bytes.
 */
@Serializable
data class TorrentDto(
    val id: String,
    val name: String,
    val state: String,
    val progress: Double,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val downloadSpeed: Long,
    val uploadSpeed: Long,
    val peers: Int,
    val etaSeconds: Long?,
    val ratio: Double,
)

/** Maps this engine snapshot to its wire shape; see [TorrentDto]. */
fun TorrentSnapshot.toDto(): TorrentDto =
    TorrentDto(
        id = id.value,
        name = name,
        state = state.toDtoState(),
        progress = round(progressPercent * 10.0) / 10.0,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        downloadSpeed = downloadRateBps,
        uploadSpeed = uploadRateBps,
        peers = peers,
        etaSeconds = etaSeconds,
        ratio = ratio,
    )

private fun DownloadState.toDtoState(): String =
    when (this) {
        DownloadState.FetchingMetadata -> "fetching_metadata"
        DownloadState.Queued -> "queued"
        DownloadState.Downloading -> "downloading"
        DownloadState.Paused -> "paused"
        DownloadState.Verifying -> "verifying"
        DownloadState.Completed -> "completed"
        DownloadState.Error -> "error"
    }
