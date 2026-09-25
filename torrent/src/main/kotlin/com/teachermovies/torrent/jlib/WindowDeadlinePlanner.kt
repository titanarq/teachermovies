package com.teachermovies.torrent.jlib

import com.teachermovies.torrent.api.PieceRange
import com.teachermovies.torrent.api.RangeReadiness

/**
 * What [JLibTorrentEngine.prioritizeWindow] applies to a torrent's handle when its read-ahead
 * window moves: [deadlines] maps each piece that entered the window to its deadline in
 * milliseconds, and [reset] lists, ascending, the pieces that left it.
 */
internal data class WindowDeadlinePlan(
    val deadlines: Map<Int, Int>,
    val reset: List<Int>,
)

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
    ): WindowDeadlinePlan = plan(previous?.pieces(), current.pieces(), deadlineStepMs)

    /**
     * The same delta for windows made of any pieces (#245: the playback gate's head, tail and start
     * buffer are not contiguous). [current] is the new window's pieces in the order their deadlines
     * should follow ([piecesOf]); [previous] is the window set last, null when none is. Pieces of
     * [current] not in [previous] get deadlines [deadlineStepMs], 2 x [deadlineStepMs], ... in
     * [current]'s order; pieces of [previous] not in [current] are reset, ascending.
     */
    fun plan(
        previous: List<Int>?,
        current: List<Int>,
        deadlineStepMs: Int,
    ): WindowDeadlinePlan {
        val before = previous?.toHashSet() ?: emptySet()
        val after = current.toHashSet()
        val deadlines = LinkedHashMap<Int, Int>()
        var step = 0
        for (piece in current) {
            if (piece in before || piece in deadlines) continue
            step += 1
            deadlines[piece] = deadlineStepMs * step
        }
        val reset = before.filterNot { it in after }.sorted()
        return WindowDeadlinePlan(deadlines, reset)
    }

    /**
     * The pieces of [ranges] as one window: each range's pieces ascending, ranges in the order
     * given, a piece shared by two ranges listed once (where it first appears).
     */
    fun piecesOf(ranges: List<PieceRange>): List<Int> {
        val pieces = LinkedHashSet<Int>()
        for (range in ranges) pieces.addAll(range.pieces())
        return pieces.toList()
    }
}

/** Every piece of this inclusive range, ascending. */
internal fun PieceRange.pieces(): List<Int> = (firstPiece..lastPiece).toList()

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
