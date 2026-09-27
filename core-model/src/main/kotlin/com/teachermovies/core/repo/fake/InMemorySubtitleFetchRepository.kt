package com.teachermovies.core.repo.fake

import com.teachermovies.core.model.SubtitleFetch
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.SubtitleFetchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

private typealias FetchKey = Pair<TorrentId, String>

/**
 * Deterministic [SubtitleFetchRepository] over an in-memory map, for other modules' JVM tests
 * (ADR-0003): no Android runtime and no real time -- [ensurePending] takes `now` from its caller
 * exactly like `RoomSubtitleFetchRepository` does, and whether a row is due is [SubtitleFetch.isDue]
 * for both, never a second copy of the ADR's retry rule.
 */
class InMemorySubtitleFetchRepository : SubtitleFetchRepository {
    private val rows = MutableStateFlow<Map<FetchKey, SubtitleFetch>>(emptyMap())

    override fun observeAll(): Flow<List<SubtitleFetch>> =
        rows.map { byKey -> byKey.values.sortedByDescending { it.updatedAtEpochMs } }

    override suspend fun get(
        id: TorrentId,
        language: String,
    ): SubtitleFetch? = rows.value[FetchKey(id, language)]

    override suspend fun dueForFetch(now: Long): List<SubtitleFetch> =
        rows.value.values
            .filter { it.isDue(now) }
            .sortedByDescending { it.updatedAtEpochMs }

    override suspend fun save(fetch: SubtitleFetch) {
        rows.update { byKey -> byKey + (FetchKey(fetch.torrentId, fetch.language) to fetch) }
    }

    override suspend fun ensurePending(
        id: TorrentId,
        language: String,
        movieHash: String?,
        now: Long,
    ): SubtitleFetch {
        val existing = rows.value[FetchKey(id, language)]
        if (existing == null) {
            val created = SubtitleFetch.pending(id, language, movieHash, now)
            save(created)
            return created
        }
        val withHash = existing.withMovieHashIfMissing(movieHash)
        if (withHash != existing) save(withHash)
        return withHash
    }

    override suspend fun delete(
        id: TorrentId,
        language: String,
    ) {
        rows.update { byKey -> byKey - FetchKey(id, language) }
    }

    override suspend fun deleteForTorrent(id: TorrentId) {
        rows.update { byKey -> byKey.filterKeys { it.first != id } }
    }

    /** [movieHash] filled into a row that has none; state, attempts and the retry clock all stay put. */
    private fun SubtitleFetch.withMovieHashIfMissing(movieHash: String?): SubtitleFetch =
        if (this.movieHash == null && movieHash != null) copy(movieHash = movieHash) else this
}
