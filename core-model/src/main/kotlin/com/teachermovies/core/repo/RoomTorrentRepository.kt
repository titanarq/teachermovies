package com.teachermovies.core.repo

import com.teachermovies.core.db.TorrentDao
import com.teachermovies.core.db.TorrentEntity
import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.LibraryItem
import com.teachermovies.core.model.Torrent
import com.teachermovies.core.model.TorrentId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [TorrentRepository] over [TorrentDao]. */
class RoomTorrentRepository(
    private val dao: TorrentDao,
) : TorrentRepository {
    override fun observeDownloads(): Flow<List<Torrent>> =
        dao.observeAll().map { entities ->
            entities.map { it.toDomain() }.filterNot { it.state == DownloadState.Completed }
        }

    override fun observeLibrary(): Flow<List<LibraryItem>> =
        dao.observeByState(DownloadState.Completed.name).map { entities ->
            entities
                .filter { it.mainFilePath != null }
                .sortedByDescending { it.completedAtEpochMs ?: 0L }
                .map { it.toLibraryItem() }
        }

    override suspend fun get(id: TorrentId): Torrent? = dao.get(id.value)?.toDomain()

    override suspend fun getLibraryItem(id: TorrentId): LibraryItem? =
        dao.get(id.value)
            ?.takeIf { it.state == DownloadState.Completed.name && it.mainFilePath != null }
            ?.toLibraryItem()

    override suspend fun upsert(
        torrent: Torrent,
        mainFilePath: String?,
        now: Long,
    ) {
        val existing = dao.get(torrent.id.value)
        dao.upsert(
            TorrentEntity(
                infoHash = torrent.id.value,
                name = torrent.name,
                state = torrent.state.name,
                progressPercent = torrent.progressPercent,
                downloadedBytes = torrent.downloadedBytes,
                totalBytes = torrent.totalBytes,
                savePath = torrent.savePath,
                mainFileIndex = torrent.mainFileIndex,
                mainFilePath = mainFilePath,
                audioTrackId = existing?.audioTrackId,
                subtitleTrackId = existing?.subtitleTrackId,
                lastPositionMs = existing?.lastPositionMs ?: 0L,
                addedAtEpochMs = existing?.addedAtEpochMs ?: now,
                completedAtEpochMs = existing.completedAtEpochMsOr(now, torrent.state),
                errorMessage = torrent.errorMessage,
            ),
        )
    }

    override suspend fun updatePlayback(
        id: TorrentId,
        positionMs: Long,
        audioTrackId: String?,
        subtitleTrackId: String?,
    ) = dao.updatePlayback(id.value, positionMs, audioTrackId, subtitleTrackId)

    override suspend fun delete(id: TorrentId) = dao.delete(id.value)
}

/**
 * `completedAtEpochMs` for the row replacing [this]: kept once set, otherwise stamped with [now] the
 * moment [newState] is [DownloadState.Completed].
 */
private fun TorrentEntity?.completedAtEpochMsOr(
    now: Long,
    newState: DownloadState,
): Long? =
    this?.completedAtEpochMs ?: if (newState == DownloadState.Completed) now else null

private fun TorrentEntity.toDomain(): Torrent =
    Torrent(
        id = TorrentId(infoHash),
        name = name,
        state = state.toDownloadStateOrError(),
        progressPercent = progressPercent,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        savePath = savePath,
        mainFileIndex = mainFileIndex,
        errorMessage = errorMessage,
    )

private fun TorrentEntity.toLibraryItem(): LibraryItem =
    LibraryItem(
        id = TorrentId(infoHash),
        title = name,
        mainFilePath = requireNotNull(mainFilePath) { "library item $infoHash has no main file path" },
        sizeBytes = totalBytes,
        lastPositionMs = lastPositionMs,
        audioTrackId = audioTrackId,
        subtitleTrackId = subtitleTrackId,
        completedAtEpochMs = completedAtEpochMs ?: 0L,
    )

/** An unknown `state` string maps to [DownloadState.Error] (acceptance criteria, #67). */
private fun String.toDownloadStateOrError(): DownloadState =
    DownloadState.entries.firstOrNull { it.name == this } ?: DownloadState.Error
