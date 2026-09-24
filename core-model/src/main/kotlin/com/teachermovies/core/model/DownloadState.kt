package com.teachermovies.core.model

/** Where a torrent is in its lifecycle, from the metadata fetch to a playable file on disk. */
enum class DownloadState {
    FetchingMetadata,
    Queued,
    Downloading,
    Paused,
    Verifying,
    Completed,
    Error,
}
