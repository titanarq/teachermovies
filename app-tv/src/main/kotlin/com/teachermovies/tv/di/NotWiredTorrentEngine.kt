package com.teachermovies.tv.di

import com.teachermovies.core.model.TorrentId
import com.teachermovies.torrent.api.EngineError
import com.teachermovies.torrent.api.EngineResult
import com.teachermovies.torrent.api.EngineStatus
import com.teachermovies.torrent.api.FilePriority
import com.teachermovies.torrent.api.TorrentEngine
import com.teachermovies.torrent.api.TorrentFileInfo
import com.teachermovies.torrent.api.TorrentSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The [TorrentEngine] `AppContainer` binds until the jlibtorrent-backed engine is wired (#55).
 *
 * Not a fake (ADR-0003 rule 3): it simulates nothing. It honestly reports that no engine is
 * running -- [engineStatus] stays [EngineStatus.Stopped], [torrents] stays empty -- and every call
 * fails with [EngineError.NotReady], so a screen showing the engine state tells the truth.
 */
internal object NotWiredTorrentEngine : TorrentEngine {
    override val engineStatus: StateFlow<EngineStatus> = MutableStateFlow(EngineStatus.Stopped).asStateFlow()

    override val torrents: StateFlow<List<TorrentSnapshot>> = MutableStateFlow(emptyList<TorrentSnapshot>()).asStateFlow()

    private val notReady = EngineResult.Failure(EngineError.NotReady)

    override suspend fun start(): EngineResult<Unit> = notReady

    override suspend fun stop() = Unit

    override suspend fun addMagnet(uri: String): EngineResult<TorrentId> = notReady

    override suspend fun addTorrentFile(bytes: ByteArray): EngineResult<TorrentId> = notReady

    override suspend fun files(id: TorrentId): EngineResult<List<TorrentFileInfo>> = notReady

    override suspend fun setFilePriorities(id: TorrentId, priorities: Map<Int, FilePriority>): EngineResult<Unit> = notReady

    override suspend fun pause(id: TorrentId): EngineResult<Unit> = notReady

    override suspend fun resume(id: TorrentId): EngineResult<Unit> = notReady

    override suspend fun remove(id: TorrentId, deleteFiles: Boolean): EngineResult<Unit> = notReady

    override suspend fun saveResumeData(): EngineResult<Unit> = notReady

    override suspend fun prioritizeWindow(
        id: TorrentId,
        fileIndex: Int,
        byteOffset: Long,
        windowBytes: Long,
    ): EngineResult<Unit> = notReady

    override suspend fun clearWindow(id: TorrentId): EngineResult<Unit> = notReady
}
