package com.teachermovies.core.repo

import com.teachermovies.core.repo.fake.InMemorySubtitleAlignmentRepository

class InMemorySubtitleAlignmentRepositoryTest : SubtitleAlignmentRepositoryContractTest() {
    override fun createRepository(): SubtitleAlignmentRepository = InMemorySubtitleAlignmentRepository()
}
