package com.teachermovies.core.repo

import com.teachermovies.core.repo.fake.InMemoryExplanationCacheRepository

/**
 * The [ExplanationCacheRepository] contract against the deterministic fake other modules' JVM tests
 * use (ADR-0003): no Android runtime, no clock -- `now` comes from each test.
 */
class InMemoryExplanationCacheRepositoryTest : ExplanationCacheRepositoryContractTest() {
    override fun createRepository(pruning: CachePruningPolicy): ExplanationCacheRepository =
        InMemoryExplanationCacheRepository(pruning)
}
