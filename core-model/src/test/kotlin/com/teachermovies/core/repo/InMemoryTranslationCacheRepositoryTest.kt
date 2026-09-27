package com.teachermovies.core.repo

import com.teachermovies.core.repo.fake.InMemoryTranslationCacheRepository

/**
 * The [TranslationCacheRepository] contract against the deterministic fake other modules' JVM tests
 * use (ADR-0003): no Android runtime, no clock -- `now` comes from each test.
 */
class InMemoryTranslationCacheRepositoryTest : TranslationCacheRepositoryContractTest() {
    override fun createRepository(pruning: CachePruningPolicy): TranslationCacheRepository =
        InMemoryTranslationCacheRepository(pruning)
}
