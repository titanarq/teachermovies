package com.teachermovies.core.repo

import com.teachermovies.core.db.SubtitleAlignmentDao
import com.teachermovies.core.db.SubtitleAlignmentEntity
import com.teachermovies.core.model.SubtitleAlignment
import com.teachermovies.core.model.TorrentId

/**
 * [SubtitleAlignmentRepository] over [SubtitleAlignmentDao] (#274). Every column maps one to one onto
 * [SubtitleAlignment], so a stored alignment comes back byte for byte; picking the best-scored file
 * is the DAO's `bestForTorrent` query, and judging that score is #283's business, not this class's.
 */
class RoomSubtitleAlignmentRepository(
    private val dao: SubtitleAlignmentDao,
) : SubtitleAlignmentRepository {
    override suspend fun get(
        id: TorrentId,
        subtitlePath: String,
    ): SubtitleAlignment? = dao.get(id.value, subtitlePath)?.toDomain()

    override suspend fun bestFor(id: TorrentId): SubtitleAlignment? = dao.bestForTorrent(id.value)?.toDomain()

    override suspend fun save(alignment: SubtitleAlignment) = dao.upsert(alignment.toEntity())

    override suspend fun delete(
        id: TorrentId,
        subtitlePath: String,
    ) = dao.delete(id.value, subtitlePath)

    override suspend fun deleteForTorrent(id: TorrentId) = dao.deleteForTorrent(id.value)
}

private fun SubtitleAlignmentEntity.toDomain(): SubtitleAlignment =
    SubtitleAlignment(
        torrentId = TorrentId(infoHash),
        subtitlePath = subtitlePath,
        offsetMs = offsetMs,
        frameRateScale = frameRateScale,
        qualityScore = qualityScore,
        computedAtEpochMs = computedAtEpochMs,
    )

private fun SubtitleAlignment.toEntity(): SubtitleAlignmentEntity =
    SubtitleAlignmentEntity(
        infoHash = torrentId.value,
        subtitlePath = subtitlePath,
        offsetMs = offsetMs,
        frameRateScale = frameRateScale,
        qualityScore = qualityScore,
        computedAtEpochMs = computedAtEpochMs,
    )
