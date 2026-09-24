package com.teachermovies.player.streaming

import com.teachermovies.core.model.TorrentId
import com.teachermovies.player.api.Player
import com.teachermovies.torrent.api.EngineError
import com.teachermovies.torrent.api.EngineResult
import com.teachermovies.torrent.api.TorrentEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Supervises playback of a file that is still downloading (ADR-0001 §4), between a [Player] and a
 * [TorrentEngine]: [start] holds the file closed until the head, tail and start buffer are on
 * disk, then opens and plays it.
 *
 * Everything runs on coroutines and virtual-time friendly `delay`s: [pollIntervalMs] paces every
 * readiness query.
 */
class StreamingPlaybackController(
    private val player: Player,
    private val engine: TorrentEngine,
    private val scope: CoroutineScope,
    private val policy: StreamPolicy = StreamPolicy.DEFAULT,
    private val pollIntervalMs: Long = 500,
    private val minWindowMoveBytes: Long = 1L shl 20,
) {
    private val mutableState = MutableStateFlow<StreamState>(StreamState.Idle)

    /** What the controller is doing; starts at [StreamState.Idle]. */
    val state: StateFlow<StreamState> = mutableState.asStateFlow()

    /**
     * Opens [file] (file [fileIndex] of torrent [id], [fileSizeBytes] long, lasting [durationMs] or
     * `0` when unknown) at [startPositionMs] once every range of [StreamWindowCalculator.openRanges]
     * is on disk, publishing [StreamState.Preparing] while it waits.
     *
     * `UnknownTorrent` and `Unsupported` engine failures answer at once, `Io` answers [StreamResult.Failed],
     * and `NotReady` (metadata still arriving) keeps polling. The file is never opened before every
     * open range is ready.
     */
    suspend fun start(
        id: TorrentId,
        fileIndex: Int,
        file: File,
        fileSizeBytes: Long,
        durationMs: Long,
        startPositionMs: Long,
    ): StreamResult {
        val ranges = StreamWindowCalculator.openRanges(fileSizeBytes, startPositionMs, durationMs, policy)
        val requiredBytes = ranges.sumOf { it.lengthBytes }
        var openRangePrioritised = ranges.isEmpty()

        while (true) {
            if (!openRangePrioritised) {
                val first = ranges.first()
                when (val result = engine.prioritizeWindow(id, fileIndex, first.offsetBytes, first.lengthBytes)) {
                    is EngineResult.Ok -> openRangePrioritised = true
                    // Metadata may still be arriving: ask again on the next tick.
                    is EngineResult.Failure -> gateFailure(result.error)?.let { return it }
                }
            }

            var readyBytes = 0L
            var allReady = true
            for (range in ranges) {
                when (val result = engine.rangeReadiness(id, fileIndex, range.offsetBytes, range.lengthBytes)) {
                    is EngineResult.Ok -> {
                        readyBytes += result.value.readyBytes.coerceIn(0L, range.lengthBytes)
                        allReady = allReady && result.value.ready
                    }
                    is EngineResult.Failure -> {
                        gateFailure(result.error)?.let { return it }
                        allReady = false
                    }
                }
            }
            if (allReady) break

            mutableState.value = StreamState.Preparing(readyBytes, requiredBytes)
            delay(pollIntervalMs)
        }

        player.open(file, startPositionMs)
        player.play()
        mutableState.value = StreamState.Streaming
        return StreamResult.Opened
    }

    /**
     * The answer [start] gives for an engine [error], or null when it keeps polling (`NotReady`).
     * Publishes the matching [StreamState] as a side effect.
     */
    private fun gateFailure(error: EngineError): StreamResult? {
        val result =
            when (error) {
                EngineError.NotReady -> return null
                EngineError.UnknownTorrent -> StreamResult.UnknownTorrent
                EngineError.Unsupported -> StreamResult.Unsupported
                is EngineError.Io -> StreamResult.Failed(error.message)
                // Not answers any range call gives; report them rather than guess.
                EngineError.InvalidMagnet,
                EngineError.InvalidTorrentFile,
                is EngineError.AlreadyExists,
                -> StreamResult.Failed("unexpected engine error: $error")
            }
        mutableState.value =
            if (result is StreamResult.Failed) StreamState.Failed(result.reason) else StreamState.Idle
        return result
    }
}
