package com.teachermovies.torrent.api

/**
 * An inclusive range of absolute piece indices inside one torrent, `firstPiece..lastPiece`, as
 * [PieceWindowCalculator.piecesFor] computes it for a byte window of one file.
 */
data class PieceRange(
    val firstPiece: Int,
    val lastPiece: Int,
)
