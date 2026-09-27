package com.teachermovies.core.repo

import com.teachermovies.core.model.SubtitleFetch
import com.teachermovies.core.model.TorrentId
import kotlinx.coroutines.flow.Flow

/**
 * Domain-level access to the automatic-subtitle search state (#274, ADR-0005 §5; #280 publishes the
 * needs, #282 drives the searches). One row per movie and language, so a film that needs both English
 * and Spanish has two, each with its own state and its own downloaded file.
 *
 * The repository stores rows; deciding *when* to search, what to search for and what a result means
 * stays with the callers. [dueForFetch] and `SubtitleFetch.isDue` are the one place the ADR's retry
 * rules live, so the bridge loop and the web UI cannot drift apart on them.
 */
interface SubtitleFetchRepository {
    /** Every row, most recently updated first; emits on collection and on every change. */
    fun observeAll(): Flow<List<SubtitleFetch>>

    suspend fun get(
        id: TorrentId,
        language: String,
    ): SubtitleFetch?

    /** Every row a fetch loop may act on at [now], most recently updated first. */
    suspend fun dueForFetch(now: Long): List<SubtitleFetch>

    /** Inserts [fetch], or replaces the row with the same movie and language. */
    suspend fun save(fetch: SubtitleFetch)

    /**
     * The movie's [language] row as a fresh pending search, created only when it has none: an existing
     * row -- downloaded, in flight, or waiting out its retry -- keeps its state, its attempts and its
     * retry clock, and only gains [movieHash] if it did not have one yet (the hash is computed from the
     * file, #279, which may happen after the movie was first seen to need subtitles). Returns the row
     * as it stands afterwards.
     */
    suspend fun ensurePending(
        id: TorrentId,
        language: String,
        movieHash: String?,
        now: Long,
    ): SubtitleFetch

    suspend fun delete(
        id: TorrentId,
        language: String,
    )

    /** Every language row of one movie; called when the torrent itself goes. */
    suspend fun deleteForTorrent(id: TorrentId)
}
