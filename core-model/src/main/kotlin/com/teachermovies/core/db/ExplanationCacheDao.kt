package com.teachermovies.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * Queries over the `explanation_cache` table, keyed by (movie title, normalized line, prompt
 * version).
 *
 * The two `delete` queries are the pruning policy's hands (`com.teachermovies.core.repo.CachePruningPolicy`):
 * every write calls them, so the table cannot outgrow its cap no matter how long the app runs.
 */
@Dao
interface ExplanationCacheDao {
    /** Inserts [entity], or replaces the row with the same title, line and prompt version. */
    @Upsert
    suspend fun upsert(entity: ExplanationCacheEntity)

    @Query(
        "SELECT * FROM explanation_cache WHERE movieTitle = :movieTitle AND line = :line " +
            "AND promptVersion = :promptVersion",
    )
    suspend fun get(
        movieTitle: String,
        line: String,
        promptVersion: String,
    ): ExplanationCacheEntity?

    /** A hit refreshes the row, so pruning keeps the lines the household actually re-watches. */
    @Query(
        "UPDATE explanation_cache SET lastUsedAtEpochMs = :now, hitCount = hitCount + 1 " +
            "WHERE movieTitle = :movieTitle AND line = :line AND promptVersion = :promptVersion",
    )
    suspend fun touch(
        movieTitle: String,
        line: String,
        promptVersion: String,
        now: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM explanation_cache")
    suspend fun count(): Int

    /** Rows last used before [staleBeforeEpochMs]; returns how many went. */
    @Query("DELETE FROM explanation_cache WHERE lastUsedAtEpochMs < :staleBeforeEpochMs")
    suspend fun deleteUsedBefore(staleBeforeEpochMs: Long): Int

    /**
     * Everything but the [maxRows] most recently used rows; returns how many went. `rowid` stands in
     * for the three-column key, which `NOT IN` cannot express.
     */
    @Query(
        "DELETE FROM explanation_cache WHERE rowid NOT IN " +
            "(SELECT rowid FROM explanation_cache ORDER BY lastUsedAtEpochMs DESC LIMIT :maxRows)",
    )
    suspend fun deleteBeyondMostRecentlyUsed(maxRows: Int): Int
}
