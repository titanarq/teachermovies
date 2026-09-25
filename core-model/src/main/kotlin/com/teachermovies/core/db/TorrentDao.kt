package com.teachermovies.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Queries over the `torrents` table. Rows are keyed by info-hash. */
@Dao
interface TorrentDao {
    /** Inserts [entity], or replaces the row with the same info-hash. */
    @Upsert
    suspend fun upsert(entity: TorrentEntity)

    @Query("SELECT * FROM torrents WHERE infoHash = :infoHash")
    suspend fun get(infoHash: String): TorrentEntity?

    /** Every torrent, newest first. */
    @Query("SELECT * FROM torrents ORDER BY addedAtEpochMs DESC")
    fun observeAll(): Flow<List<TorrentEntity>>

    /** Torrents whose `state` equals [state], newest first. */
    @Query("SELECT * FROM torrents WHERE state = :state ORDER BY addedAtEpochMs DESC")
    fun observeByState(state: String): Flow<List<TorrentEntity>>

    /**
     * The library (#247): every row that has completed once (`completedAtEpochMs` set) and has a main
     * file path, whatever its current `state` -- paused, re-checking or seeding rows stay listed;
     * only [delete] removes one. Newest completed first.
     */
    @Query(
        "SELECT * FROM torrents WHERE completedAtEpochMs IS NOT NULL AND mainFilePath IS NOT NULL " +
            "ORDER BY completedAtEpochMs DESC",
    )
    fun observeLibrary(): Flow<List<TorrentEntity>>

    /** Updates the download progress columns; a missing info-hash is a no-op. */
    @Query(
        "UPDATE torrents SET state = :state, progressPercent = :progressPercent, " +
            "downloadedBytes = :downloadedBytes, totalBytes = :totalBytes WHERE infoHash = :infoHash",
    )
    suspend fun updateProgress(
        infoHash: String,
        state: String,
        progressPercent: Double,
        downloadedBytes: Long,
        totalBytes: Long,
    )

    /** Updates the resume position and chosen tracks; a missing info-hash is a no-op. */
    @Query(
        "UPDATE torrents SET lastPositionMs = :lastPositionMs, audioTrackId = :audioTrackId, " +
            "subtitleTrackId = :subtitleTrackId WHERE infoHash = :infoHash",
    )
    suspend fun updatePlayback(
        infoHash: String,
        lastPositionMs: Long,
        audioTrackId: String?,
        subtitleTrackId: String?,
    )

    @Query("DELETE FROM torrents WHERE infoHash = :infoHash")
    suspend fun delete(infoHash: String)
}
