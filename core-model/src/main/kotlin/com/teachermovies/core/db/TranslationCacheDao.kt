package com.teachermovies.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * Queries over the `translation_cache` table, keyed by the normalized English source text.
 *
 * The two `delete` queries are the pruning policy's hands (`com.teachermovies.core.repo.CachePruningPolicy`):
 * every write calls them, so the table cannot outgrow its cap no matter how long the app runs.
 */
@Dao
interface TranslationCacheDao {
    /** Inserts [entity], or replaces the row with the same source text. */
    @Upsert
    suspend fun upsert(entity: TranslationCacheEntity)

    @Query("SELECT * FROM translation_cache WHERE sourceText = :sourceText")
    suspend fun get(sourceText: String): TranslationCacheEntity?

    /** A hit refreshes the row, so pruning keeps the lines the household actually re-watches. */
    @Query(
        "UPDATE translation_cache SET lastUsedAtEpochMs = :now, hitCount = hitCount + 1 " +
            "WHERE sourceText = :sourceText",
    )
    suspend fun touch(
        sourceText: String,
        now: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM translation_cache")
    suspend fun count(): Int

    /** Rows last used before [staleBeforeEpochMs]; returns how many went. */
    @Query("DELETE FROM translation_cache WHERE lastUsedAtEpochMs < :staleBeforeEpochMs")
    suspend fun deleteUsedBefore(staleBeforeEpochMs: Long): Int

    /** Everything but the [maxRows] most recently used rows; returns how many went. */
    @Query(
        "DELETE FROM translation_cache WHERE sourceText NOT IN " +
            "(SELECT sourceText FROM translation_cache ORDER BY lastUsedAtEpochMs DESC LIMIT :maxRows)",
    )
    suspend fun deleteBeyondMostRecentlyUsed(maxRows: Int): Int
}
