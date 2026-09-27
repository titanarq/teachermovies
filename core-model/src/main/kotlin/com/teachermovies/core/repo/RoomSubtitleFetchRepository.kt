package com.teachermovies.core.repo

import com.teachermovies.core.db.SubtitleFetchStateDao
import com.teachermovies.core.db.SubtitleFetchStateEntity
import com.teachermovies.core.model.SubtitleFetch
import com.teachermovies.core.model.SubtitleFetchState
import com.teachermovies.core.model.TorrentId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [SubtitleFetchRepository] over [SubtitleFetchStateDao] (#274). Rows are stored verbatim: nothing is
 * derived or defaulted on the way in, and the retry rules of ADR-0005 §5 stay in [SubtitleFetch] so
 * this class and `InMemorySubtitleFetchRepository` cannot drift apart on when a search is due.
 */
class RoomSubtitleFetchRepository(
    private val dao: SubtitleFetchStateDao,
) : SubtitleFetchRepository {
    override fun observeAll(): Flow<List<SubtitleFetch>> = dao.observeAll().map(::toDomainList)

    override suspend fun get(
        id: TorrentId,
        language: String,
    ): SubtitleFetch? = dao.get(id.value, language)?.toDomain()

    /** [SubtitleFetchStateDao.getAll] has no `ORDER BY`, so the ordering happens here, not in SQL. */
    override suspend fun dueForFetch(now: Long): List<SubtitleFetch> =
        toDomainList(dao.getAll())
            .filter { it.isDue(now) }
            .sortedByDescending { it.updatedAtEpochMs }

    override suspend fun save(fetch: SubtitleFetch) = dao.upsert(fetch.toEntity())

    override suspend fun ensurePending(
        id: TorrentId,
        language: String,
        movieHash: String?,
        now: Long,
    ): SubtitleFetch {
        val existing = dao.get(id.value, language)?.toDomain()
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
    ) = dao.delete(id.value, language)

    override suspend fun deleteForTorrent(id: TorrentId) = dao.deleteForTorrent(id.value)
}

/** [movieHash] filled into a row that has none; state, attempts and the retry clock all stay put. */
private fun SubtitleFetch.withMovieHashIfMissing(movieHash: String?): SubtitleFetch =
    if (this.movieHash == null && movieHash != null) copy(movieHash = movieHash) else this

private fun toDomainList(rows: List<SubtitleFetchStateEntity>): List<SubtitleFetch> = rows.map { it.toDomain() }

private fun SubtitleFetchStateEntity.toDomain(): SubtitleFetch =
    SubtitleFetch(
        torrentId = TorrentId(infoHash),
        language = language,
        state = state.toSubtitleFetchStateOrFailed(),
        movieHash = movieHash,
        localPath = localPath,
        variantLabel = variantLabel,
        attempts = attempts,
        lastAttemptEpochMs = lastAttemptEpochMs,
        nextRetryEpochMs = nextRetryEpochMs,
        errorMessage = errorMessage,
        updatedAtEpochMs = updatedAtEpochMs,
    )

private fun SubtitleFetch.toEntity(): SubtitleFetchStateEntity =
    SubtitleFetchStateEntity(
        infoHash = torrentId.value,
        language = language,
        state = state.name,
        movieHash = movieHash,
        localPath = localPath,
        variantLabel = variantLabel,
        attempts = attempts,
        lastAttemptEpochMs = lastAttemptEpochMs,
        nextRetryEpochMs = nextRetryEpochMs,
        errorMessage = errorMessage,
        updatedAtEpochMs = updatedAtEpochMs,
    )

/** An unknown `state` string maps to [SubtitleFetchState.Failed], as `RoomTorrentRepository` does. */
private fun String.toSubtitleFetchStateOrFailed(): SubtitleFetchState =
    SubtitleFetchState.entries.firstOrNull { it.name == this } ?: SubtitleFetchState.Failed
