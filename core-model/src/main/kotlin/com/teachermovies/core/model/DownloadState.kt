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
    ;

    /**
     * Whether this state may move to [next]. Staying in the current state is always allowed, so an
     * engine that reports the same status twice is not a rejected transition.
     */
    fun canTransitionTo(next: DownloadState): Boolean {
        if (next == this) return true
        return next in
            when (this) {
                FetchingMetadata -> setOf(Queued, Downloading, Paused, Error)
                Queued -> setOf(Downloading, Paused, Error)
                Downloading -> setOf(Verifying, Completed, Paused, Queued, Error)
                Paused -> setOf(Queued, Downloading, FetchingMetadata, Verifying)
                Verifying -> setOf(Downloading, Completed, Error)
                Completed -> setOf(Verifying, Paused)
                Error -> setOf(Queued, FetchingMetadata, Verifying)
            }
    }
}
