package com.teachermovies.core.repo.fake

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.LibraryItem
import com.teachermovies.core.model.Torrent
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.TorrentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Deterministic [TorrentRepository] over an in-memory map, for other modules' JVM tests (ADR-0003):
 * no Android runtime, no real time -- [upsert] takes `now` from its caller exactly like
 * `RoomTorrentRepository` does.
 */
class InMemoryTorrentRepository : TorrentRepository {
    private data class Row(
        val torrent: Torrent,
        val mainFilePath: String?,
        val audioTrackId: String?,
        val subtitleTrackId: String?,
        val lastPositionMs: Long,
        val addedAtEpochMs: Long,
        val completedAtEpochMs: Long?,
    )

    private val rows = MutableStateFlow<Map<TorrentId, Row>>(emptyMap())

    override fun observeDownloads(): Flow<List<Torrent>> =
        rows.map { byId ->
            byId.values
                .filterNot { it.torrent.state == DownloadState.Completed }
                .sortedByDescending { it.addedAtEpochMs }
                .map { it.torrent }
        }

    override fun observeLibrary(): Flow<List<LibraryItem>> =
        rows.map { byId ->
            byId.values
                .filter { it.isInLibrary() }
                .sortedByDescending { it.completedAtEpochMs ?: 0L }
                .map { it.toLibraryItem() }
        }

    override suspend fun get(id: TorrentId): Torrent? = rows.value[id]?.torrent

    override suspend fun getLibraryItem(id: TorrentId): LibraryItem? =
        rows.value[id]
            ?.takeIf { it.isInLibrary() }
            ?.toLibraryItem()

    override suspend fun getPlaybackItem(id: TorrentId): LibraryItem? =
        rows.value[id]
            ?.takeIf { it.mainFilePath != null }
            ?.toLibraryItem()

    override suspend fun upsert(
        torrent: Torrent,
        mainFilePath: String?,
        now: Long,
    ) {
        rows.update { byId ->
            val existing = byId[torrent.id]
            byId +
                (
                    torrent.id to
                        Row(
                            torrent = torrent,
                            mainFilePath = mainFilePath,
                            audioTrackId = existing?.audioTrackId,
                            subtitleTrackId = existing?.subtitleTrackId,
                            lastPositionMs = existing?.lastPositionMs ?: 0L,
                            addedAtEpochMs = existing?.addedAtEpochMs ?: now,
                            completedAtEpochMs = existing.completedAtEpochMsOr(now, torrent.state),
                        )
                )
        }
    }

    override suspend fun updatePlayback(
        id: TorrentId,
        positionMs: Long,
        audioTrackId: String?,
        subtitleTrackId: String?,
    ) {
        rows.update { byId ->
            val existing = byId[id] ?: return@update byId
            byId +
                (
                    id to
                        existing.copy(
                            lastPositionMs = positionMs,
                            audioTrackId = audioTrackId,
                            subtitleTrackId = subtitleTrackId,
                        )
                )
        }
    }

    override suspend fun delete(id: TorrentId) {
        rows.update { byId -> byId - id }
    }

    /** Completed once and a main file is known, whatever the current state (#247). */
    private fun Row.isInLibrary(): Boolean = completedAtEpochMs != null && mainFilePath != null

    private fun Row?.completedAtEpochMsOr(
        now: Long,
        newState: DownloadState,
    ): Long? = this?.completedAtEpochMs ?: if (newState == DownloadState.Completed) now else null

    private fun Row.toLibraryItem(): LibraryItem =
        LibraryItem(
            id = torrent.id,
            title = torrent.name,
            mainFilePath = requireNotNull(mainFilePath) { "library item ${torrent.id} has no main file path" },
            sizeBytes = torrent.totalBytes,
            lastPositionMs = lastPositionMs,
            audioTrackId = audioTrackId,
            subtitleTrackId = subtitleTrackId,
            completedAtEpochMs = completedAtEpochMs ?: 0L,
        )
}
