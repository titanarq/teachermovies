package com.teachermovies.core.model

/**
 * A torrent as the download list shows it.
 *
 * [progressPercent] runs from 0.0 to 100.0, not 0.0 to 1.0. [savePath] and [mainFileIndex] stay
 * null until the metadata is known and the file to play has been picked; [errorMessage] carries the
 * engine's own text while [state] is [DownloadState.Error].
 */
data class Torrent(
    val id: TorrentId,
    val name: String,
    val state: DownloadState,
    val progressPercent: Double,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val savePath: String?,
    val mainFileIndex: Int?,
    val errorMessage: String?,
)
