package com.teachermovies.torrent.api

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.TorrentId

/**
 * One torrent's live state, as [TorrentEngine.torrents] reports it.
 *
 * [progressPercent] runs from 0.0 to 100.0, not 0.0 to 1.0. [etaSeconds] is null while it cannot be
 * estimated (no peers, no metadata yet); [ratio] is uploaded bytes over downloaded bytes.
 * [hasMetadata] is false until the torrent's file list and total size are known, which is what a
 * caller waits on before calling [TorrentEngine.files]. [savePath] stays null until metadata is
 * known; [errorMessage] carries the engine's own text while [state] is [DownloadState.Error].
 * [mainFileIndex] is the [TorrentFileInfo.index] of the movie file the engine chose automatically
 * when metadata arrived (`FileSelectionPolicy`), or null before metadata or when the torrent has no
 * video file.
 */
data class TorrentSnapshot(
    val id: TorrentId,
    val name: String,
    val state: DownloadState,
    val progressPercent: Double,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val downloadRateBps: Long,
    val uploadRateBps: Long,
    val peers: Int,
    val etaSeconds: Long?,
    val ratio: Double,
    val hasMetadata: Boolean,
    val savePath: String?,
    val errorMessage: String?,
    val mainFileIndex: Int? = null,
)
