package com.teachermovies.torrent.policy

import com.teachermovies.core.model.DownloadState

/**
 * Decides the user-visible [DownloadState] from the engine's [RawStatus], so the jlibtorrent
 * adapter (#52) carries no state logic of its own.
 */
object DownloadStateMapper {
    /**
     * Applies the rules in order; the first one that matches wins. In particular an error beats
     * a paused status, and a paused status beats a still-fetching-metadata one.
     */
    fun map(raw: RawStatus): DownloadState =
        when {
            raw.hasError -> DownloadState.Error
            raw.paused && !raw.autoManagedQueued -> DownloadState.Paused
            raw.paused && raw.autoManagedQueued -> DownloadState.Queued
            !raw.hasMetadata || raw.phase == RawPhase.DownloadingMetadata -> DownloadState.FetchingMetadata
            raw.phase == RawPhase.CheckingFiles || raw.phase == RawPhase.CheckingResumeData -> DownloadState.Verifying
            raw.phase == RawPhase.Finished || raw.phase == RawPhase.Seeding || raw.isFinished -> DownloadState.Completed
            raw.phase == RawPhase.Downloading -> DownloadState.Downloading
            else -> DownloadState.Queued
        }
}

/**
 * Estimated seconds remaining at the current [rateBps], or `null` when the rate is not positive
 * and no estimate can be made.
 */
fun etaSeconds(
    remainingBytes: Long,
    rateBps: Long,
): Long? {
    if (rateBps <= 0) return null
    // Integer ceiling division: exact for arbitrarily large byte counts, unlike a Double round trip.
    return (remainingBytes + rateBps - 1) / rateBps
}
