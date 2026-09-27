package com.teachermovies.core.repo

import com.teachermovies.core.db.ExplanationCacheDao
import com.teachermovies.core.db.ExplanationCacheEntity

/**
 * [ExplanationCacheRepository] over [ExplanationCacheDao] (#274). A write ends with the two pruning
 * deletes, so `explanation_cache` cannot outgrow [pruning] no matter how long the app runs; a read
 * only touches its own row and never grows the table.
 *
 * The payload stays the bridge's JSON verbatim: it is stored and handed back, never parsed.
 */
class RoomExplanationCacheRepository(
    private val dao: ExplanationCacheDao,
    private val pruning: CachePruningPolicy = CachePruningPolicy(),
) : ExplanationCacheRepository {
    override suspend fun explanationFor(
        movieTitle: String,
        line: String,
        promptVersion: String,
        now: Long,
    ): String? {
        val title = normalizeCacheText(movieTitle)
        val lineKey = normalizeCacheText(line)
        val version = normalizeCacheText(promptVersion)
        if (title.isBlank() || lineKey.isBlank()) return null
        val row = dao.get(title, lineKey, version) ?: return null
        // A hit refreshes the row, which is what keeps a re-watched line out of the pruning's way.
        dao.touch(title, lineKey, version, now)
        return row.explanationJson
    }

    override suspend fun store(
        movieTitle: String,
        line: String,
        promptVersion: String,
        explanationJson: String,
        now: Long,
    ) {
        val title = normalizeCacheText(movieTitle)
        val lineKey = normalizeCacheText(line)
        val version = normalizeCacheText(promptVersion)
        // ADR-0005 §8: only successes are cached, and an empty answer is not one. Nothing is written
        // and nothing is pruned, so a failed ask cannot evict what the household already has. A blank
        // prompt version is a key part like any other, not a reason to drop the answer.
        if (title.isBlank() || lineKey.isBlank() || explanationJson.isBlank()) return
        val existing = dao.get(title, lineKey, version)
        dao.upsert(
            ExplanationCacheEntity(
                movieTitle = title,
                line = lineKey,
                promptVersion = version,
                explanationJson = explanationJson,
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
