package com.teachermovies.torrent.api

import com.teachermovies.core.model.TorrentId
import kotlinx.coroutines.flow.StateFlow

/**
 * The one contract every other module programs against for downloading torrents.
 *
 * No jlibtorrent type crosses this boundary (ADR-0001 §3-4, `docs/modules/torrent.md`): every
 * module above `:torrent` depends only on this interface and the value types in this package, plus
 * [TorrentId] and `DownloadState` from `:core-model`. Tests above this module use a fake
 * implementation instead of the real jlibtorrent-backed one.
 *
 * [engineStatus] and [torrents] emit their current value on collection and then on every change, so
 * a collector never has to poll.
 */
interface TorrentEngine {
    /** The engine's own lifecycle, independent of any one torrent's state. */
    val engineStatus: StateFlow<EngineStatus>

    /** Every torrent the engine currently knows about. */
    val torrents: StateFlow<List<TorrentSnapshot>>

    /** Starts the engine (the foreground service's session). Safe to call while already running. */
    suspend fun start(): EngineResult<Unit>

    /** Stops the engine. Does not remove any torrent; a restart resumes them. */
    suspend fun stop()

    /** Adds a torrent from a magnet URI, returning its [TorrentId] once accepted. */
    suspend fun addMagnet(uri: String): EngineResult<TorrentId>

    /** Adds a torrent from the raw bytes of a `.torrent` file, returning its [TorrentId]. */
    suspend fun addTorrentFile(bytes: ByteArray): EngineResult<TorrentId>

    /** Lists the files inside the torrent [id], once its metadata is known. */
    suspend fun files(id: TorrentId): EngineResult<List<TorrentFileInfo>>

    /**
     * Sets the [FilePriority] of one or more files in the torrent [id].
     *
     * [priorities] maps a file's [TorrentFileInfo.index] to the priority it should have; files left
     * out of the map keep their current priority.
     */
    suspend fun setFilePriorities(
        id: TorrentId,
        priorities: Map<Int, FilePriority>,
    ): EngineResult<Unit>

    /** Pauses the torrent [id]; it stops exchanging pieces but stays known to the engine. */
    suspend fun pause(id: TorrentId): EngineResult<Unit>

    /** Resumes a paused (or queued) torrent [id]. */
    suspend fun resume(id: TorrentId): EngineResult<Unit>

    /** Removes the torrent [id] from the engine, deleting its downloaded files when [deleteFiles]. */
    suspend fun remove(
        id: TorrentId,
        deleteFiles: Boolean,
    ): EngineResult<Unit>

    /** Persists resume data for every torrent, so downloads continue after a restart. */
    suspend fun saveResumeData(): EngineResult<Unit>

    /**
     * The stream-while-downloading hook (ADR-0001 §4): asks the engine to prioritise the pieces
     * covering `[byteOffset, byteOffset + windowBytes)` of file [fileIndex] in torrent [id], ahead of
     * the torrent's normal piece order, so playback can read ahead of the download.
     *
     * Implementations may return `Failure(EngineError.Unsupported)` until the window-prioritisation
     * feature itself is implemented (epic E7); the interface carries the hook from day 1 so no
     * caller above this module needs to change when it lands.
     */
    suspend fun prioritizeWindow(
        id: TorrentId,
        fileIndex: Int,
        byteOffset: Long,
        windowBytes: Long,
    ): EngineResult<Unit>

    /**
     * The multi-range form of [prioritizeWindow] (#245): replaces torrent [id]'s window with the
     * pieces covering every range of [ranges] in file [fileIndex] at once -- what the playback gate
     * needs before opening an incomplete file (head, tail and start buffer, which are not
     * contiguous). Deadlines follow the order of [ranges], ascending piece order within each one.
     *
     * One window per torrent still holds: a later [prioritizeWindow] or [prioritizeRanges] call
     * replaces this one, and [clearWindow] clears it. `prioritizeRanges(id, i, listOf(r))` is the
     * same as `prioritizeWindow(id, i, r.offsetBytes, r.lengthBytes)`; an empty [ranges] clears the
     * window. Failures as for [prioritizeWindow].
     */
    suspend fun prioritizeRanges(
        id: TorrentId,
        fileIndex: Int,
        ranges: List<FileByteRange>,
    ): EngineResult<Unit>

    /**
     * Clears any window set by [prioritizeWindow] or [prioritizeRanges] for torrent [id], returning
     * to normal ordering.
     */
    suspend fun clearWindow(id: TorrentId): EngineResult<Unit>

    /**
     * The readiness query playback uses before opening or resuming an incomplete file (epic #8,
     * ADR-0001 §4): reports whether `[byteOffset, byteOffset + lengthBytes)` of file [fileIndex] in
     * torrent [id] is on disk, how many bytes are available contiguously from [byteOffset], and
     * which covering pieces are still missing (see [RangeReadiness]). Pieces arrive out of order, so
     * the torrent's overall progress cannot answer this.
     *
     * The covering pieces are those [PieceWindowCalculator.piecesFor] computes; a [lengthBytes] of
     * zero or less asks about the single piece containing [byteOffset].
     *
     * Returns `Failure(EngineError.UnknownTorrent)` for an unknown [id] and
     * `Failure(EngineError.NotReady)` before the torrent's metadata is known.
     */
    suspend fun rangeReadiness(
        id: TorrentId,
        fileIndex: Int,
        byteOffset: Long,
        lengthBytes: Long,
    ): EngineResult<RangeReadiness>
}
