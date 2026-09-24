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
     * Whether this state may move to [next].
     *
     * The rule: a move is legal unless it would undo something the engine cannot undo. Metadata is
     * never lost once known, so the only rejected moves are `Downloading -> FetchingMetadata`,
     * `Verifying -> FetchingMetadata` and `Completed -> FetchingMetadata`; every other move between
     * two different states is allowed. `Verifying` belongs to that group because it is only
     * reachable with metadata known: `DownloadStateMapper` answers `FetchingMetadata` while
     * `hasMetadata` is false. Staying in the current state is always allowed, so an engine that
     * reports the same status twice is not a rejected transition.
     *
     * The moves an earlier, narrower table rejected are real, as `DownloadStateMapper` derives them:
     * - `Queued -> Verifying` when a queued torrent starts checking files;
     * - `FetchingMetadata -> Completed` when an already finished torrent is re-added;
     * - `Completed -> Downloading` when a skipped file is un-skipped;
     * - a move to `Error`, `Paused` or `Queued` from any state, because the mapper's error and
     *   paused rules win over the phase.
     */
    fun canTransitionTo(next: DownloadState): Boolean {
        if (next == this) return true
        return !(next == FetchingMetadata && this in METADATA_KNOWN)
    }

    private companion object {
        /** States that imply the metadata is known, so they can never go back to fetching it. */
        val METADATA_KNOWN = setOf(Downloading, Verifying, Completed)
    }
}
