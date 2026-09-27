package com.teachermovies.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** Queries over the `subtitle_alignment` table, keyed by (info-hash, subtitle path). */
@Dao
interface SubtitleAlignmentDao {
    /** Inserts [entity], or replaces the row for the same file of the same movie. */
    @Upsert
    suspend fun upsert(entity: SubtitleAlignmentEntity)

    @Query(
        "SELECT * FROM subtitle_alignment WHERE infoHash = :infoHash AND subtitlePath = :subtitlePath",
    )
    suspend fun get(
        infoHash: String,
        subtitlePath: String,
    ): SubtitleAlignmentEntity?

    /**
     * The best-scored alignment stored for [infoHash], or null when it has none. Two rows with the
     * same score are broken by the most recently computed one, so the answer never depends on which
     * order SQLite happened to store them in.
     */
    @Query(
        "SELECT * FROM subtitle_alignment WHERE infoHash = :infoHash " +
            "ORDER BY qualityScore DESC, computedAtEpochMs DESC LIMIT 1",
    )
    suspend fun bestForTorrent(infoHash: String): SubtitleAlignmentEntity?

    @Query("DELETE FROM subtitle_alignment WHERE infoHash = :infoHash AND subtitlePath = :subtitlePath")
    suspend fun delete(
        infoHash: String,
        subtitlePath: String,
    )

    /** Every alignment of one movie; called when the torrent itself is deleted. */
    @Query("DELETE FROM subtitle_alignment WHERE infoHash = :infoHash")
    suspend fun deleteForTorrent(infoHash: String)
}
