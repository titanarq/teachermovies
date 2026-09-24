package com.teachermovies.player.streaming

import com.teachermovies.core.model.TorrentId
import com.teachermovies.player.api.Player
import com.teachermovies.player.api.PlayerState
import com.teachermovies.torrent.api.EngineError
import com.teachermovies.torrent.api.EngineResult
import com.teachermovies.torrent.api.TorrentEngine
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Supervises playback of a file that is still downloading (ADR-0001 §4), between a [Player] and a
 * [TorrentEngine]:
 *
 * - [start] holds the file closed until the head, tail and start buffer are on disk, then opens
 *   and plays it.
 * - While it plays, the playback position is fed to the engine as a sliding read-ahead window
 *   ([StreamWindowCalculator.windowFor]), re-sent only once it has moved at least
 *   [minWindowMoveBytes] -- which is also how a seek reaches the engine, as a jump in
 *   [Player.positionMs].
 * - Every [pollIntervalMs] it asks how many bytes are ready ahead of the position and applies
 *   [StreamPolicy.decide]: it pauses the player on an underrun ([StreamState.Buffering]) and plays
 *   it again once enough bytes are back. It only ever resumes a pause it caused itself; a pause the
 *   viewer asked for stays until the player plays again.
 * - [stop], [PlayerState.Ended] and [PlayerState.Error] end supervision and clear the window.
 *
 * Everything runs on coroutines and `delay`s, in [scope], so tests drive it on virtual time.
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

    /** Guards [session] so [stop] and a player-driven end never tear the same session down twice. */
    private val sessionLock = Mutex()

    private var session: Session? = null

    /** One supervised file: what it is, the job running its loops, and the buffering decision. */
    private class Session(
        val id: TorrentId,
        val fileIndex: Int,
        val fileSizeBytes: Long,
        val fallbackDurationMs: Long,
    ) {
        lateinit var job: Job

        /** Guards the three fields below, which the buffer loop and the state watcher share. */
        val lock = Mutex()
        var decision: BufferDecision = BufferDecision.Play

        /** The controller called `pause()` and has not seen the player play since. */
        var pausedByController = false

        /** The player paused without the controller asking; never undone by the controller. */
        var viewerPaused = false
    }

    /**
     * Opens [file] (file [fileIndex] of torrent [id], [fileSizeBytes] long, lasting [durationMs] or
     * `0` when unknown) at [startPositionMs] once every range of [StreamWindowCalculator.openRanges]
     * is on disk, publishing [StreamState.Preparing] while it waits, and then supervises it.
     *
     * `UnknownTorrent` and `Unsupported` engine failures answer at once, `Io` answers [StreamResult.Failed],
     * and `NotReady` (metadata still arriving) keeps polling. The file is never opened before every
     * open range is ready. A session still supervised from an earlier call is stopped first.
     */
    suspend fun start(
        id: TorrentId,
        fileIndex: Int,
        file: File,
        fileSizeBytes: Long,
        durationMs: Long,
        startPositionMs: Long,
    ): StreamResult {
        stop()
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

        val supervised = Session(id, fileIndex, fileSizeBytes, durationMs)
        supervised.job =
            scope.launch(start = CoroutineStart.LAZY) {
                launch { feedWindow(supervised) }
                launch { superviseBuffer(supervised) }
                launch { watchPlayer(supervised) }
            }
        sessionLock.withLock { session = supervised }
        supervised.job.start()
        return StreamResult.Opened
    }

    /**
     * Cancels both loops, clears the engine's window and publishes [StreamState.Idle]. A no-op on
     * a controller that supervises nothing.
     */
    suspend fun stop() {
        finish(expected = null, finalState = StreamState.Idle)
    }

    /**
     * Tears down the current session -- only if it is still [expected], when one is given -- and
     * publishes [finalState].
     */
    private suspend fun finish(
        expected: Session?,
        finalState: StreamState,
    ) {
        sessionLock.withLock {
            val current = session ?: return
            if (expected != null && current !== expected) return
            session = null
            current.job.cancelAndJoin()
            // Nothing left to supervise whatever the engine answers: a torrent removed meanwhile
            // (`UnknownTorrent`) has no window to clear anyway.
            engine.clearWindow(current.id)
            mutableState.value = finalState
        }
    }

    /**
     * Sends the read-ahead window at the player's position to the engine whenever its offset has
     * moved at least [minWindowMoveBytes] since the last window the engine accepted. A failed call
     * leaves the last offset alone, so the next position update tries again.
     */
    private suspend fun feedWindow(session: Session) {
        var lastOffset: Long? = null
        combine(player.positionMs, player.durationMs) { position, duration -> position to duration }
            .collect { (position, duration) ->
                val window =
                    StreamWindowCalculator.windowFor(position, session.duration(duration), session.fileSizeBytes, policy)
                val last = lastOffset
                if (last != null && abs(window.offsetBytes - last) < minWindowMoveBytes) return@collect
                val result = engine.prioritizeWindow(session.id, session.fileIndex, window.offsetBytes, window.lengthBytes)
                if (result is EngineResult.Ok) lastOffset = window.offsetBytes
            }
    }

    /**
     * Every [pollIntervalMs], asks how many of the [StreamPolicy.resumeBytes] ahead of the position
     * are ready and applies [StreamPolicy.decide]. A failed query keeps the current decision until
     * the next tick answers.
     */
    private suspend fun superviseBuffer(session: Session) {
        while (true) {
            val offset =
                StreamWindowCalculator.byteOffsetFor(
                    player.positionMs.value,
                    session.duration(player.durationMs.value),
                    session.fileSizeBytes,
                )
            val readiness = engine.rangeReadiness(session.id, session.fileIndex, offset, policy.resumeBytes)
            if (readiness is EngineResult.Ok) {
                val atEndOfFile = offset + policy.resumeBytes >= session.fileSizeBytes
                applyDecision(session, readiness.value.readyBytes, atEndOfFile)
            }
            delay(pollIntervalMs)
        }
    }

    private suspend fun applyDecision(
        session: Session,
        readyBytes: Long,
        atEndOfFile: Boolean,
    ) = session.lock.withLock {
        val next = policy.decide(session.decision, readyBytes, atEndOfFile)
        val previous = session.decision
        session.decision = next
        when {
            previous == BufferDecision.Play && next == BufferDecision.Wait -> {
                if (!session.viewerPaused) {
                    session.pausedByController = true
                    player.pause()
                }
                mutableState.value = StreamState.Buffering(readyBytes, policy.resumeBytes)
            }
            previous == BufferDecision.Wait && next == BufferDecision.Play -> {
                mutableState.value = StreamState.Streaming
                if (!session.viewerPaused) player.play()
            }
            next == BufferDecision.Wait -> mutableState.value = StreamState.Buffering(readyBytes, policy.resumeBytes)
            else -> Unit
        }
    }

    /**
     * Tells the viewer's pauses from the controller's own, and ends the session when the player
     * reaches [PlayerState.Ended] or [PlayerState.Error].
     */
    private suspend fun watchPlayer(session: Session) {
        val end =
            player.state.first { playerState ->
                when (playerState) {
                    PlayerState.Paused ->
                        session.lock.withLock {
                            if (!session.pausedByController) session.viewerPaused = true
                        }
                    PlayerState.Playing ->
                        session.lock.withLock {
                            session.viewerPaused = false
                            session.pausedByController = false
                        }
                    PlayerState.Idle, PlayerState.Opening, PlayerState.Ended, is PlayerState.Error -> Unit
                }
                playerState == PlayerState.Ended || playerState is PlayerState.Error
            }
        val finalState = if (end is PlayerState.Error) StreamState.Failed(end.message) else StreamState.Idle
        // Launched outside the session's job: tearing it down cancels this very coroutine.
        scope.launch { finish(expected = session, finalState = finalState) }
    }

    /** The player's parsed [duration], or what the caller knew until it has one. */
    private fun Session.duration(duration: Long): Long = if (duration > 0) duration else fallbackDurationMs

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
