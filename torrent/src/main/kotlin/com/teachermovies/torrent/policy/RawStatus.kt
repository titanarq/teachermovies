package com.teachermovies.torrent.policy

/**
 * The engine's raw torrent phase, mirroring libtorrent's `torrent_status::state_t` without
 * referencing it: no jlibtorrent type may cross the `:torrent` module boundary (ADR-0001 §4).
 */
enum class RawPhase {
    CheckingFiles,
    DownloadingMetadata,
    Downloading,
    Finished,
    Seeding,
    CheckingResumeData,
    Unknown,
}

/**
 * A snapshot of everything [DownloadStateMapper] needs from the engine to decide the
 * user-visible [com.teachermovies.core.model.DownloadState], deliberately narrower than
 * `torrent_status` itself.
 */
data class RawStatus(
    val phase: RawPhase,
    val paused: Boolean,
    val autoManagedQueued: Boolean,
    val hasMetadata: Boolean,
    val hasError: Boolean,
    val isFinished: Boolean,
)
