package com.teachermovies.core.repo

import com.teachermovies.core.model.LibraryItem
import com.teachermovies.core.model.Torrent
import com.teachermovies.core.model.TorrentId
import kotlinx.coroutines.flow.Flow

/**
 * Domain-level access to persisted torrents; other modules go through this instead of `TorrentDao`
 * so a schema detail (entity shape, raw `state` string) never leaks past `:core-model`.
 *
 * [observeDownloads] and [observeLibrary] emit their current value on collection and then on every
 * change, so a collector never has to poll. Neither implementation keeps this repository's own view
 * of the torrent engine in sync -- that mapping is #68, out of scope here.
 */
interface TorrentRepository {
    /** Every torrent not yet [com.teachermovies.core.model.DownloadState.Completed]. */
    fun observeDownloads(): Flow<List<Torrent>>

    /** Completed torrents with a known main file, newest completed first. */
    fun observeLibrary(): Flow<List<LibraryItem>>

    suspend fun get(id: TorrentId): Torrent?

    /** Same as [get], but only when [id] is completed with a known main file. */
    suspend fun getLibraryItem(id: TorrentId): LibraryItem?

    /**
     * Inserts [torrent], or replaces the row with the same [Torrent.id].
     *
     * [mainFilePath] becomes the row's stored main file path -- pass the existing value back if the
     * caller does not want to change it. An existing row's `addedAtEpochMs` is kept; `now` is used
     * only to stamp a new row's `addedAtEpochMs`, and to stamp `completedAtEpochMs` the first time
     * [torrent]'s state becomes [com.teachermovies.core.model.DownloadState.Completed] -- once set,
     * `completedAtEpochMs` never changes. The chosen playback tracks and resume position are left
     * untouched; use [updatePlayback] for those.
     */
    suspend fun upsert(
        torrent: Torrent,
        mainFilePath: String?,
        now: Long,
    )

    /** Updates the resume position and chosen tracks for [id]; a missing [id] is a no-op. */
    suspend fun updatePlayback(
        id: TorrentId,
        positionMs: Long,
        audioTrackId: String?,
        subtitleTrackId: String?,
    )

    suspend fun delete(id: TorrentId)
}
