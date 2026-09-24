package com.teachermovies.torrent.fake

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.TorrentId
import com.teachermovies.torrent.api.EngineError
import com.teachermovies.torrent.api.EngineResult
import com.teachermovies.torrent.api.EngineStatus
import com.teachermovies.torrent.api.FilePriority
import com.teachermovies.torrent.api.MagnetUri
import com.teachermovies.torrent.api.PieceWindowCalculator
import com.teachermovies.torrent.api.RangeReadiness
import com.teachermovies.torrent.api.TorrentEngine
import com.teachermovies.torrent.api.TorrentFileInfo
import com.teachermovies.torrent.api.TorrentSnapshot
import com.teachermovies.torrent.policy.FileSelectionPolicy
import com.teachermovies.torrent.policy.etaSeconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

/**
 * A deterministic, in-memory [TorrentEngine] (ADR-0003): every other module's tests drive it
 * through metadata, progress, completion and errors without a real jlibtorrent session, real
 * time, or sleeps. State lives in [MutableStateFlow]s; the test-only control methods below
 * ([emitMetadata], [advance], [complete], [fail], [setEngineStatus], [setPieces], [lastWindow])
 * are not part of [TorrentEngine] and exist only to drive this fake from a test.
 */
class FakeTorrentEngine : TorrentEngine {
    private val _engineStatus = MutableStateFlow(EngineStatus.Stopped)
    override val engineStatus: StateFlow<EngineStatus> = _engineStatus.asStateFlow()

    private val _torrents = MutableStateFlow<List<TorrentSnapshot>>(emptyList())
    override val torrents: StateFlow<List<TorrentSnapshot>> = _torrents.asStateFlow()

    private val filesByTorrent = mutableMapOf<TorrentId, List<TorrentFileInfo>>()

    /** The piece model per torrent, set by [setPieces]: piece length and the pieces on disk. */
    private data class PieceModel(
        val pieceLengthBytes: Int,
        val have: Set<Int>,
    )

    private val piecesByTorrent = mutableMapOf<TorrentId, PieceModel>()

    private val windows = mutableMapOf<TorrentId, Triple<Int, Long, Long>>()

    private val _recordedCalls = mutableListOf<String>()

    /**
     * Every [setFilePriorities], [pause], [resume], [remove], [prioritizeWindow] and [clearWindow]
     * call, in order and whether or not it succeeded, for assertions (e.g. `"remove(<id>,true)"`,
     * `"setFilePriorities(<id>,{0=Skip})"`).
     */
    val recordedCalls: List<String> get() = _recordedCalls.toList()

    override suspend fun start(): EngineResult<Unit> {
        _engineStatus.value = EngineStatus.Running
        return EngineResult.Ok(Unit)
    }

    override suspend fun stop() {
        _engineStatus.value = EngineStatus.Stopped
    }

    override suspend fun addMagnet(uri: String): EngineResult<TorrentId> {
        val magnet = MagnetUri.parse(uri) ?: return EngineResult.Failure(EngineError.InvalidMagnet)
        val id = TorrentId(magnet.infoHash)
        if (snapshotOf(id) != null) return EngineResult.Failure(EngineError.AlreadyExists(id))
        addTorrent(id, name = magnet.displayName ?: magnet.infoHash)
        return EngineResult.Ok(id)
    }

    override suspend fun addTorrentFile(bytes: ByteArray): EngineResult<TorrentId> {
        if (bytes.isEmpty() || bytes[0] != 'd'.code.toByte()) {
            return EngineResult.Failure(EngineError.InvalidTorrentFile)
        }
        val hash = sha1Hex(bytes)
        val id = TorrentId(hash)
        if (snapshotOf(id) != null) return EngineResult.Failure(EngineError.AlreadyExists(id))
        addTorrent(id, name = hash)
        return EngineResult.Ok(id)
    }

    override suspend fun files(id: TorrentId): EngineResult<List<TorrentFileInfo>> {
        if (snapshotOf(id) == null) return EngineResult.Failure(EngineError.UnknownTorrent)
        return EngineResult.Ok(filesByTorrent[id].orEmpty())
    }

    override suspend fun setFilePriorities(
        id: TorrentId,
        priorities: Map<Int, FilePriority>,
    ): EngineResult<Unit> {
        _recordedCalls += "setFilePriorities(${id.value},$priorities)"
        if (snapshotOf(id) == null) return EngineResult.Failure(EngineError.UnknownTorrent)
        val updated =
            filesByTorrent[id].orEmpty().map { file ->
                priorities[file.index]?.let { file.copy(priority = it) } ?: file
            }
        filesByTorrent[id] = updated
        recomputeTotalBytes(id)
        return EngineResult.Ok(Unit)
    }

    override suspend fun pause(id: TorrentId): EngineResult<Unit> {
        _recordedCalls += "pause(${id.value})"
        if (snapshotOf(id) == null) return EngineResult.Failure(EngineError.UnknownTorrent)
        replaceSnapshot(id) { it.copy(state = DownloadState.Paused) }
        return EngineResult.Ok(Unit)
    }

    override suspend fun resume(id: TorrentId): EngineResult<Unit> {
        _recordedCalls += "resume(${id.value})"
        val snapshot = snapshotOf(id) ?: return EngineResult.Failure(EngineError.UnknownTorrent)
        val next = if (snapshot.hasMetadata) DownloadState.Downloading else DownloadState.Queued
        replaceSnapshot(id) { it.copy(state = next) }
        return EngineResult.Ok(Unit)
    }

    override suspend fun remove(
        id: TorrentId,
        deleteFiles: Boolean,
    ): EngineResult<Unit> {
        _recordedCalls += "remove(${id.value},$deleteFiles)"
        if (snapshotOf(id) == null) return EngineResult.Failure(EngineError.UnknownTorrent)
        _torrents.value = _torrents.value.filterNot { it.id == id }
        filesByTorrent.remove(id)
        piecesByTorrent.remove(id)
        windows.remove(id)
        return EngineResult.Ok(Unit)
    }

    override suspend fun saveResumeData(): EngineResult<Unit> = EngineResult.Ok(Unit)

    override suspend fun prioritizeWindow(
        id: TorrentId,
        fileIndex: Int,
        byteOffset: Long,
        windowBytes: Long,
    ): EngineResult<Unit> {
        _recordedCalls += "prioritizeWindow(${id.value},$fileIndex,$byteOffset,$windowBytes)"
        windows[id] = Triple(fileIndex, byteOffset, windowBytes)
        return EngineResult.Ok(Unit)
    }

    override suspend fun clearWindow(id: TorrentId): EngineResult<Unit> {
        _recordedCalls += "clearWindow(${id.value})"
        windows.remove(id)
        return EngineResult.Ok(Unit)
    }

    /**
     * Answers from the [setPieces] model through [PieceWindowCalculator]; `readyBytes` stops at the
     * first missing piece and at the end of the file. A [fileIndex] the metadata does not list is
     * `Failure(EngineError.NotReady)`.
     */
    override suspend fun rangeReadiness(
        id: TorrentId,
        fileIndex: Int,
        byteOffset: Long,
        lengthBytes: Long,
    ): EngineResult<RangeReadiness> {
        val snapshot = snapshotOf(id) ?: return EngineResult.Failure(EngineError.UnknownTorrent)
        if (!snapshot.hasMetadata) return EngineResult.Failure(EngineError.NotReady)
        val files = filesByTorrent[id].orEmpty()
        val file = files.getOrNull(fileIndex) ?: return EngineResult.Failure(EngineError.NotReady)
        val model = piecesByTorrent[id] ?: PieceModel(DEFAULT_PIECE_LENGTH_BYTES, emptySet())
        val pieceLength = model.pieceLengthBytes.toLong()
        val torrentBytes = files.sumOf { it.sizeBytes }
        val totalPieces = ((torrentBytes + pieceLength - 1) / pieceLength).coerceAtLeast(1L).toInt()
        val fileOffset = files.take(fileIndex).sumOf { it.sizeBytes }
        val range =
            PieceWindowCalculator.piecesFor(
                fileOffset,
                model.pieceLengthBytes,
                totalPieces,
                byteOffset,
                lengthBytes,
            )
        val missing = (range.firstPiece..range.lastPiece).filterNot { it in model.have }
        val start = fileOffset + byteOffset
        val windowEnd = (start + lengthBytes.coerceAtLeast(0L)).coerceAtMost(fileOffset + file.sizeBytes)
        val contiguousEnd = missing.firstOrNull()?.let { it * pieceLength } ?: Long.MAX_VALUE
        val readyBytes = (minOf(windowEnd, contiguousEnd) - start).coerceAtLeast(0L)
        return EngineResult.Ok(
            RangeReadiness(ready = missing.isEmpty(), readyBytes = readyBytes, missingPieces = missing),
        )
    }

    // -- Test controls: not part of TorrentEngine, drive the fake deterministically from a test. --

    /**
     * Delivers metadata for torrent [id]: moves it to [DownloadState.Downloading], records its
     * [files], applies the same automatic selection as the real engine ([FileSelectionPolicy]: the
     * main video and subtitles at [FilePriority.Normal], everything else at [FilePriority.Skip]),
     * sets `mainFileIndex` to the chosen movie file and `totalBytes` to the sum of the files that
     * are not skipped.
     */
    fun emitMetadata(
        id: TorrentId,
        name: String,
        files: List<Pair<String, Long>>,
    ) {
        val listed =
            files.mapIndexed { index, (path, sizeBytes) ->
                TorrentFileInfo(
                    index = index,
                    path = path,
                    sizeBytes = sizeBytes,
                    priority = FilePriority.Normal,
                    downloadedBytes = 0L,
                )
            }
        val selection = FileSelectionPolicy.select(listed)
        val fileInfos =
            listed.map { file ->
                selection.priorities[file.index]?.let { file.copy(priority = it) } ?: file
            }
        filesByTorrent[id] = fileInfos
        val totalBytes = fileInfos.filter { it.priority != FilePriority.Skip }.sumOf { it.sizeBytes }
        replaceSnapshot(id) { snapshot ->
            snapshot.copy(
                name = name,
                state = DownloadState.Downloading,
                totalBytes = totalBytes,
                hasMetadata = true,
                savePath = "/movies/${id.value}",
                mainFileIndex = selection.mainFileIndex,
            )
        }
    }

    /** Advances torrent [id] by [bytes] downloaded, updating rate, peers, progress and ETA. */
    fun advance(
        id: TorrentId,
        bytes: Long,
        rateBps: Long = 0,
        peers: Int = 0,
    ) {
        replaceSnapshot(id) { snapshot ->
            val cap = if (snapshot.totalBytes > 0) snapshot.totalBytes else Long.MAX_VALUE
            val downloaded = (snapshot.downloadedBytes + bytes).coerceIn(0L, cap)
            val remaining = (snapshot.totalBytes - downloaded).coerceAtLeast(0L)
            val progress =
                if (snapshot.totalBytes > 0) {
                    ((downloaded.toDouble() / snapshot.totalBytes) * 100.0).coerceIn(0.0, 100.0)
                } else {
                    0.0
                }
            snapshot.copy(
                state = DownloadState.Downloading,
                downloadedBytes = downloaded,
                progressPercent = progress,
                downloadRateBps = rateBps,
                peers = peers,
                etaSeconds = etaSeconds(remaining, rateBps),
            )
        }
    }

    /** Marks torrent [id] as [DownloadState.Completed], fully downloaded. */
    fun complete(id: TorrentId) {
        replaceSnapshot(id) { snapshot ->
            snapshot.copy(
                state = DownloadState.Completed,
                downloadedBytes = snapshot.totalBytes,
                progressPercent = 100.0,
                downloadRateBps = 0L,
                etaSeconds = null,
            )
        }
    }

    /** Marks torrent [id] as [DownloadState.Error] with [message]. */
    fun fail(
        id: TorrentId,
        message: String,
    ) {
        replaceSnapshot(id) { it.copy(state = DownloadState.Error, errorMessage = message) }
    }

    /**
     * Sets the piece model of torrent [id]: the torrent's files (in [emitMetadata] order) are laid
     * out back to back in pieces of [pieceLengthBytes], and [have] lists the absolute indices of the
     * pieces on disk. [rangeReadiness] answers from this model; until this is called the torrent has
     * pieces of [DEFAULT_PIECE_LENGTH_BYTES] and none of them on disk.
     */
    fun setPieces(
        id: TorrentId,
        pieceLengthBytes: Int,
        have: Set<Int>,
    ) {
        require(pieceLengthBytes > 0) { "pieceLengthBytes must be positive: $pieceLengthBytes" }
        piecesByTorrent[id] = PieceModel(pieceLengthBytes, have.toSet())
    }

    /**
     * The last window [prioritizeWindow] set for torrent [id] as (fileIndex, byteOffset,
     * windowBytes), or null if none was set or [clearWindow] cleared it.
     */
    fun lastWindow(id: TorrentId): Triple<Int, Long, Long>? = windows[id]

    /** Sets [engineStatus] directly, independent of any one torrent. */
    fun setEngineStatus(status: EngineStatus) {
        _engineStatus.value = status
    }

    private fun snapshotOf(id: TorrentId): TorrentSnapshot? = _torrents.value.firstOrNull { it.id == id }

    private fun addTorrent(
        id: TorrentId,
        name: String,
    ) {
        _torrents.value =
            _torrents.value +
            TorrentSnapshot(
                id = id,
                name = name,
                state = DownloadState.FetchingMetadata,
                progressPercent = 0.0,
                downloadedBytes = 0L,
                totalBytes = 0L,
                downloadRateBps = 0L,
                uploadRateBps = 0L,
                peers = 0,
                etaSeconds = null,
                ratio = 0.0,
                hasMetadata = false,
                savePath = null,
                errorMessage = null,
            )
    }

    private fun replaceSnapshot(
        id: TorrentId,
        transform: (TorrentSnapshot) -> TorrentSnapshot,
    ) {
        val current = snapshotOf(id) ?: return
        _torrents.value = _torrents.value.map { if (it.id == id) transform(current) else it }
    }

    private fun recomputeTotalBytes(id: TorrentId) {
        val totalBytes = filesByTorrent[id].orEmpty().filter { it.priority != FilePriority.Skip }.sumOf { it.sizeBytes }
        replaceSnapshot(id) { snapshot ->
            val progress =
                if (totalBytes > 0) {
                    ((snapshot.downloadedBytes.toDouble() / totalBytes) * 100.0).coerceIn(0.0, 100.0)
                } else {
                    0.0
                }
            snapshot.copy(totalBytes = totalBytes, progressPercent = progress)
        }
    }

    companion object {
        /** The piece length [rangeReadiness] assumes for a torrent [setPieces] has not configured. */
        const val DEFAULT_PIECE_LENGTH_BYTES: Int = 256 * 1024
    }

    private fun sha1Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
