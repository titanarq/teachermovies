package com.teachermovies.torrent.jlib

import com.teachermovies.torrent.api.PieceRange
import com.teachermovies.torrent.api.RangeReadiness

/**
 * What [JLibTorrentEngine.prioritizeWindow] applies to a torrent's handle when its read-ahead
 * window moves: [deadlines] maps each piece that entered the window to its deadline in
 * milliseconds, and [reset] lists, ascending, the pieces that left it.
 */
internal data class WindowDeadlinePlan(val deadlines: Map<Int, Int>, val reset: List<Int>)

/**
 * Pure planning behind the real engine's read-ahead window (#94): plain values only, no jlibtorrent
 * type, so it is JVM-tested without loading the native library.
 */
internal object WindowDeadlinePlanner {
    /**
     * The delta from the window [previous] (null when none is set) to [current]. Every piece of
     * [current] not already in [previous] gets a deadline, in ascending piece order: the first one
     * [deadlineStepMs], each following one [deadlineStepMs] more, so the piece nearest the playback
     * position is asked for first. Pieces of [previous] outside [current] are listed in
     * [WindowDeadlinePlan.reset], ascending. Pieces already in both windows keep the deadline they
     * have, so an unchanged window plans nothing.
     */
    fun plan(
        previous: PieceRange?,
        current: PieceRange,
        deadlineStepMs: Int,
    ): WindowDeadlinePlan {
        val deadlines = LinkedHashMap<Int, Int>()
        var step = 0
        for (piece in current.firstPiece..current.lastPiece) {
            if (previous != null && previous.covers(piece)) continue
            step += 1
            deadlines[piece] = deadlineStepMs * step
        }
        val reset =
            if (previous == null) emptyList() else (previous.firstPiece..previous.lastPiece).filterNot { current.covers(it) }
        return WindowDeadlinePlan(deadlines, reset)
    }
}

/** Whether [piece] lies inside this inclusive range. */
internal fun PieceRange.covers(piece: Int): Boolean = piece in firstPiece..lastPiece

/**
 * Readiness of `[byteOffset, byteOffset + lengthBytes)` of a file that starts [fileOffsetInTorrent]
 * bytes into a torrent of [pieceLengthBytes]-byte pieces, where [range] is the covering pieces
 * (from `PieceWindowCalculator.piecesFor`) and [have] says whether a piece is on disk.
 *
 * `missingPieces` are the covering pieces [have] rejects, ascending; `ready` is true when there are
 * none; `readyBytes` is how many bytes are contiguous from [byteOffset] up to the first missing
 * piece, at most [lengthBytes] (zero for a non-positive [lengthBytes]).
 */
internal fun readinessFor(
    range: PieceRange,
    pieceLengthBytes: Int,
    fileOffsetInTorrent: Long,
    byteOffset: Long,
    lengthBytes: Long,
    have: (Int) -> Boolean,
): RangeReadiness {
    val missing = (range.firstPiece..range.lastPiece).filterNot(have)
    val start = fileOffsetInTorrent + byteOffset
    val end = start + lengthBytes.coerceAtLeast(0L)
    val contiguousEnd = missing.firstOrNull()?.let { it.toLong() * pieceLengthBytes } ?: Long.MAX_VALUE
    val readyBytes = (minOf(end, contiguousEnd) - start).coerceAtLeast(0L)
    return RangeReadiness(ready = missing.isEmpty(), readyBytes = readyBytes, missingPieces = missing)
}
