package com.teachermovies.core.repo

/**
 * The persistent English -> Castilian Spanish translation cache (#274; #287 is the consumer that asks
 * the bridge and stores what comes back). ADR-0005 §8: only successes are cached, so a hit means the
 * bridge did answer this exact line once.
 *
 * Both methods take [now] from the caller, the way `TorrentRepository.upsert` does, so
 * `InMemoryTranslationCacheRepository` needs no real clock and a test can age a row out on purpose.
 */
interface TranslationCacheRepository {
    /**
     * The cached Spanish for [sourceText], or null on a miss. A hit counts as a use and refreshes the
     * row, which is what keeps it out of the pruning policy's way.
     */
    suspend fun translationOf(
        sourceText: String,
        now: Long,
    ): String?

    /**
     * Caches one successful translation, replacing any row for the same [sourceText], then prunes back
     * inside the policy. A blank [sourceText] or a blank [translationEs] is stored as nothing at all:
     * an empty answer is not a success and must not shadow the next attempt.
     */
    suspend fun store(
        sourceText: String,
        translationEs: String,
        now: Long,
    )
}
