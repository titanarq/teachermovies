package com.teachermovies.core.repo

import com.teachermovies.core.repo.fake.InMemoryTorrentRepository

class InMemoryTorrentRepositoryTest : TorrentRepositoryContractTest() {
    override fun createRepository(): TorrentRepository = InMemoryTorrentRepository()
}
