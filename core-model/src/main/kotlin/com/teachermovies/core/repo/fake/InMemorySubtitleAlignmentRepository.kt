package com.teachermovies.core.repo.fake

import com.teachermovies.core.model.SubtitleAlignment
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.SubtitleAlignmentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

private typealias AlignmentKey = Pair<TorrentId, String>

/**
 * Deterministic [SubtitleAlignmentRepository] over an in-memory map, for other modules' JVM tests
 * (ADR-0003): no Android runtime and no real time -- [SubtitleAlignment.computedAtEpochMs] comes from
 * the aligner that built the row (#283), never from a clock here. [bestFor] picks the highest
 * [SubtitleAlignment.qualityScore] of one movie's files, the most recently computed one breaking a
 * tie, as the DAO's `bestForTorrent` query does; deciding how good that score has to be stays with the
 * caller.
 */
class InMemorySubtitleAlignmentRepository : SubtitleAlignmentRepository {
    private val rows = MutableStateFlow<Map<AlignmentKey, SubtitleAlignment>>(emptyMap())

    override suspend fun get(
        id: TorrentId,
        subtitlePath: String,
    ): SubtitleAlignment? = rows.value[AlignmentKey(id, subtitlePath)]

    override suspend fun bestFor(id: TorrentId): SubtitleAlignment? =
        rows.value.values
            .filter { it.torrentId == id }
            .maxWithOrNull(compareBy({ it.qualityScore }, { it.computedAtEpochMs }))

    override suspend fun save(alignment: SubtitleAlignment) {
        rows.update { byKey -> byKey + (AlignmentKey(alignment.torrentId, alignment.subtitlePath) to alignment) }
    }

    override suspend fun delete(
        id: TorrentId,
        subtitlePath: String,
    ) {
        rows.update { byKey -> byKey - AlignmentKey(id, subtitlePath) }
    }

    override suspend fun deleteForTorrent(id: TorrentId) {
        rows.update { byKey -> byKey.filterKeys { it.first != id } }
    }
}
