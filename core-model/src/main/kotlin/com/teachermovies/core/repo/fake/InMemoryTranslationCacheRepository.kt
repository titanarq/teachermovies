package com.teachermovies.core.repo.fake

import com.teachermovies.core.repo.CachePruningPolicy
import com.teachermovies.core.repo.TranslationCacheRepository
import com.teachermovies.core.repo.normalizeCacheText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Deterministic [TranslationCacheRepository] over an in-memory map, for other modules' JVM tests
 * (ADR-0003): no Android runtime, no real time -- `now` comes from the caller exactly like
 * `RoomTranslationCacheRepository`, so a test can age a row out on purpose.
 */
class InMemoryTranslationCacheRepository(
    private val pruning: CachePruningPolicy = CachePruningPolicy(),
) : TranslationCacheRepository {
    private data class Row(
        val translationEs: String,
        val createdAtEpochMs: Long,
        val lastUsedAtEpochMs: Long,
        val hitCount: Int,
    )

    private val rows = MutableStateFlow<Map<String, Row>>(emptyMap())

    override suspend fun translationOf(
        sourceText: String,
        now: Long,
    ): String? {
        val key = normalizeCacheText(sourceText)
        if (key.isBlank()) return null
        val row = rows.value[key] ?: return null
        rows.update { byKey -> byKey.touched(key, now) }
        return row.translationEs
    }

    override suspend fun store(
        sourceText: String,
        translationEs: String,
        now: Long,
    ) {
        val key = normalizeCacheText(sourceText)
        // ADR-0005 §8: only successes are cached. Nothing is written and nothing is pruned, so a
        // failed ask cannot evict what the household already has.
        if (key.isBlank() || translationEs.isBlank()) return
        rows.update { byKey ->
            byKey.stored(key, translationEs, now).prunedForCache(now, pruning) { it.lastUsedAtEpochMs }
        }
    }

    /** [key]'s row with one more hit and `lastUsedAtEpochMs = now`; the map unchanged if it is gone. */
    private fun Map<String, Row>.touched(
        key: String,
        now: Long,
    ): Map<String, Row> {
        val row = this[key] ?: return this
        val hit = row.copy(lastUsedAtEpochMs = now, hitCount = row.hitCount + 1)
        return this + (key to hit)
    }

    /**
     * [key]'s row holding [translationEs], keeping the `createdAtEpochMs` and `hitCount` of the row it
     * replaces: re-asking the bridge about one line is not a new fact about it.
     */
    private fun Map<String, Row>.stored(
        key: String,
        translationEs: String,
        now: Long,
    ): Map<String, Row> {
        val existing = this[key]
        val row =
            Row(
                translationEs = translationEs,
                createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                lastUsedAtEpochMs = now,
                hitCount = existing?.hitCount ?: 0,
            )
        return this + (key to row)
    }
}

/**
 * [pruning] applied to an in-memory map, shared by both cache fakes so they cannot drift: it is the
 * stand-in for the two `delete` queries the Room DAOs run on every write, in the same order -- rows
 * last used before [CachePruningPolicy.staleBeforeEpochMs] go first, then everything but the
 * [CachePruningPolicy.maxRows] most recently used. Ties break on the key's `toString()`, because a
 * fake has to be deterministic where SQLite is merely arbitrary.
 */
internal fun <K, R> Map<K, R>.prunedForCache(
    now: Long,
    pruning: CachePruningPolicy,
    lastUsedAtEpochMs: (R) -> Long,
): Map<K, R> {
    val staleBefore = pruning.staleBeforeEpochMs(now)
    val fresh = entries.filter { lastUsedAtEpochMs(it.value) >= staleBefore }
    val newestFirst = compareByDescending<Map.Entry<K, R>> { lastUsedAtEpochMs(it.value) }
    val ranking = newestFirst.thenBy { it.key.toString() }
    val survivors = fresh.sortedWith(ranking).take(pruning.maxRows)
    return survivors.associate { it.key to it.value }
}
