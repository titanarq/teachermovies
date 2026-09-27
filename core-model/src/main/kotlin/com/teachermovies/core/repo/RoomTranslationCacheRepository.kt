package com.teachermovies.core.repo

import com.teachermovies.core.db.TranslationCacheDao
import com.teachermovies.core.db.TranslationCacheEntity

/**
 * [TranslationCacheRepository] over [TranslationCacheDao] (#274). A write ends with the two pruning
 * deletes, so `translation_cache` cannot outgrow [pruning] no matter how long the app runs; a read
 * only touches its own row and never grows the table.
 */
class RoomTranslationCacheRepository(
    private val dao: TranslationCacheDao,
    private val pruning: CachePruningPolicy = CachePruningPolicy(),
) : TranslationCacheRepository {
    override suspend fun translationOf(
        sourceText: String,
        now: Long,
    ): String? {
        val sourceKey = normalizeCacheText(sourceText)
        if (sourceKey.isBlank()) return null
        val row = dao.get(sourceKey) ?: return null
        // A hit refreshes the row, which is what keeps a re-watched line out of the pruning's way.
        dao.touch(sourceKey, now)
        return row.translationEs
    }

    override suspend fun store(
        sourceText: String,
        translationEs: String,
        now: Long,
    ) {
        val sourceKey = normalizeCacheText(sourceText)
        // ADR-0005 §8: only successes are cached, and an empty answer is not one. Nothing is written
        // and nothing is pruned, so a failed ask cannot evict what the household already has.
        if (sourceKey.isBlank() || translationEs.isBlank()) return
        val existing = dao.get(sourceKey)
        dao.upsert(
            TranslationCacheEntity(
                sourceText = sourceKey,
                translationEs = translationEs,
                createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                lastUsedAtEpochMs = now,
                hitCount = existing?.hitCount ?: 0,
            ),
        )
        // Stale rows go first, then the least recently used ones above the cap, in that order.
        dao.deleteUsedBefore(pruning.staleBeforeEpochMs(now))
        dao.deleteBeyondMostRecentlyUsed(pruning.maxRows)
    }
}
