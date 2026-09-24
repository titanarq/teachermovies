package com.teachermovies.torrent.api

/**
 * Pure piece-window arithmetic shared by every [TorrentEngine] implementation: maps a byte window
 * inside one file to the absolute pieces of the torrent that cover it. Plain values only, no
 * Android and no jlibtorrent type.
 */
object PieceWindowCalculator {
    /**
     * The pieces covering `[byteOffset, byteOffset + lengthBytes)` of a file that starts at
     * [fileOffsetInTorrent] bytes into the torrent, for a torrent of [totalPieces] pieces of
     * [pieceLengthBytes] each.
     *
     * The window is file-relative; the result is in absolute piece indices, clamped to
     * `0..totalPieces - 1`. A [lengthBytes] of zero or less means the single piece that contains
     * [byteOffset].
     *
     * @throws IllegalArgumentException when [pieceLengthBytes] or [totalPieces] is not positive.
     */
    fun piecesFor(
        fileOffsetInTorrent: Long,
        pieceLengthBytes: Int,
        totalPieces: Int,
        byteOffset: Long,
        lengthBytes: Long,
    ): PieceRange {
        require(pieceLengthBytes > 0) { "pieceLengthBytes must be positive: $pieceLengthBytes" }
        require(totalPieces > 0) { "totalPieces must be positive: $totalPieces" }
        val pieceLength = pieceLengthBytes.toLong()
        val start = fileOffsetInTorrent + byteOffset
        val firstRaw = Math.floorDiv(start, pieceLength)
        val lastRaw = if (lengthBytes <= 0) firstRaw else Math.floorDiv(start + lengthBytes - 1, pieceLength)
        val maxPiece = (totalPieces - 1).toLong()
        return PieceRange(
            firstPiece = firstRaw.coerceIn(0L, maxPiece).toInt(),
            lastPiece = lastRaw.coerceIn(0L, maxPiece).toInt(),
        )
    }
}
