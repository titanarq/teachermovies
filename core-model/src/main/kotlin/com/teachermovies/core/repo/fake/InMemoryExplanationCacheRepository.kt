package com.teachermovies.core.repo.fake

import com.teachermovies.core.repo.CachePruningPolicy
import com.teachermovies.core.repo.ExplanationCacheRepository
import com.teachermovies.core.repo.normalizeCacheText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** The normalized (movie title, line, prompt version) the rows are keyed by. */
private typealias ExplanationKey = Triple<String, String, String>

/**
 * Deterministic [ExplanationCacheRepository] over an in-memory map, for other modules' JVM tests
 * (ADR-0003): no Android runtime, no real time -- `now` comes from the caller exactly like
 * `RoomExplanationCacheRepository`, so a test can age a row out on purpose.
 *
 * Claude explains a line in the context of a film, with the prompt the bridge happens to be running
 * (ADR-0005 §7, #292), so all three name the row and the payload stays the bridge's JSON verbatim.
 */
class InMemoryExplanationCacheRepository(
    private val pruning: CachePruningPolicy = CachePruningPolicy(),
) : ExplanationCacheRepository {
    private data class Row(
        val explanationJson: String,
        val createdAtEpochMs: Long,
        val lastUsedAtEpochMs: Long,
        val hitCount: Int,
    )

    private val rows = MutableStateFlow<Map<ExplanationKey, Row>>(emptyMap())

    override suspend fun explanationFor(
        movieTitle: String,
        line: String,
        promptVersion: String,
        now: Long,
    ): String? {
        val key = keyOf(movieTitle, line, promptVersion)
        if (key.first.isBlank() || key.second.isBlank()) return null
        val row = rows.value[key] ?: return null
        rows.update { byKey -> byKey.touched(key, now) }
        return row.explanationJson
    }

    override suspend fun store(
        movieTitle: String,
        line: String,
        promptVersion: String,
        explanationJson: String,
        now: Long,
    ) {
        val key = keyOf(movieTitle, line, promptVersion)
        // ADR-0005 §8: only successes are cached. Nothing is written and nothing is pruned, so a
        // failed ask cannot evict what the household already has. A blank prompt version is a key
        // part like any other, not a reason to drop the answer.
        if (key.first.isBlank() || key.second.isBlank() || explanationJson.isBlank()) return
        rows.update { byKey ->
            byKey.stored(key, explanationJson, now).prunedForCache(now, pruning) { it.lastUsedAtEpochMs }
        }
    }

    private fun keyOf(
        movieTitle: String,
        line: String,
        promptVersion: String,
    ): ExplanationKey =
        Triple(
            normalizeCacheText(movieTitle),
            normalizeCacheText(line),
            normalizeCacheText(promptVersion),
        )

    /** [key]'s row with one more hit and `lastUsedAtEpochMs = now`; the map unchanged if it is gone. */
    private fun Map<ExplanationKey, Row>.touched(
        key: ExplanationKey,
        now: Long,
    ): Map<ExplanationKey, Row> {
        val row = this[key] ?: return this
        val hit = row.copy(lastUsedAtEpochMs = now, hitCount = row.hitCount + 1)
        return this + (key to hit)
    }

    /**
     * [key]'s row holding [explanationJson], keeping the `createdAtEpochMs` and `hitCount` of the row
     * it replaces: asking about one line of one film under one prompt twice is not a new fact about
     * it, and it must not cost the daily cap again.
     */
    private fun Map<ExplanationKey, Row>.stored(
        key: ExplanationKey,
        explanationJson: String,
        now: Long,
    ): Map<ExplanationKey, Row> {
        val existing = this[key]
        val row =
            Row(
                explanationJson = explanationJson,
                createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                lastUsedAtEpochMs = now,
                hitCount = existing?.hitCount ?: 0,
            )
        return this + (key to row)
    }
}
