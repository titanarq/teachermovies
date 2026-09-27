package com.teachermovies.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Queries over the `subtitle_fetch_state` table, keyed by (info-hash, language).
 *
 * There is no `due` query here on purpose: whether a row is due is `SubtitleFetch.isDue(now)`, one
 * rule for the Room repository and the fake alike, so the SQL only lists rows.
 */
@Dao
interface SubtitleFetchStateDao {
    /** Inserts [entity], or replaces the row with the same info-hash and language. */
    @Upsert
    suspend fun upsert(entity: SubtitleFetchStateEntity)

    @Query("SELECT * FROM subtitle_fetch_state WHERE infoHash = :infoHash AND language = :language")
    suspend fun get(
        infoHash: String,
        language: String,
    ): SubtitleFetchStateEntity?

    /** Every row, most recently updated first -- what the phone's subtitle-needs list reads. */
    @Query("SELECT * FROM subtitle_fetch_state ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<SubtitleFetchStateEntity>>

    @Query("SELECT * FROM subtitle_fetch_state")
    suspend fun getAll(): List<SubtitleFetchStateEntity>

    @Query("DELETE FROM subtitle_fetch_state WHERE infoHash = :infoHash AND language = :language")
    suspend fun delete(
        infoHash: String,
        language: String,
    )

    /** Every language row of one movie; called when the torrent itself is deleted. */
    @Query("DELETE FROM subtitle_fetch_state WHERE infoHash = :infoHash")
    suspend fun deleteForTorrent(infoHash: String)
}
