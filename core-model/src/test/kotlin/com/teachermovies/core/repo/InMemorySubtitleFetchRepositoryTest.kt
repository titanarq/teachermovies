package com.teachermovies.core.repo

import com.teachermovies.core.repo.fake.InMemorySubtitleFetchRepository

class InMemorySubtitleFetchRepositoryTest : SubtitleFetchRepositoryContractTest() {
    override fun createRepository(): SubtitleFetchRepository = InMemorySubtitleFetchRepository()
}
