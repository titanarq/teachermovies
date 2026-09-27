package com.teachermovies.core.repo

/**
 * The bound on the Room v2 caches (#274: "a pruning policy so the caches do not grow unbounded").
 *
 * Every cache repository applies it on each [write][TranslationCacheRepository.store], in this order:
 * rows last used more than [maxAgeDays] ago go first, then the least recently used rows above
 * [maxRows]. A read never grows a cache, it only refreshes the row's `lastUsedAtEpochMs`, so what
 * survives is what the household keeps asking for.
 *
 * Two rows that share a `lastUsedAtEpochMs` are equally recent, and which of them the row cap keeps
 * is the storage's choice: the policy bounds how many rows there are, not which of two equally used
 * ones wins. A test that cares stores them at different times.
 *
 * The defaults are one household's worth: 5000 lines is roughly a megabyte, and a translation nobody
 * has re-watched for 90 days is cheaper to ask the bridge again than to keep.
 */
data class CachePruningPolicy(
    val maxRows: Int = 5_000,
    val maxAgeDays: Int = 90,
) {
    init {
        require(maxRows > 0) { "maxRows must be positive, was $maxRows" }
        require(maxAgeDays > 0) { "maxAgeDays must be positive, was $maxAgeDays" }
    }

    /** Rows last used before this instant -- given [now] -- are stale and go on the next write. */
    fun staleBeforeEpochMs(now: Long): Long = now - maxAgeDays * MILLIS_PER_DAY

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000L
    }
}
