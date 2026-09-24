package com.teachermovies.torrent.api

import org.junit.Assert.assertEquals
import org.junit.Test

class PieceWindowCalculatorTest {
    private val pieceLength = 100

    @Test
    fun aWindowInsideOnePieceIsThatPiece() {
        val range = PieceWindowCalculator.piecesFor(0L, pieceLength, 10, byteOffset = 210L, lengthBytes = 50L)

        assertEquals(PieceRange(2, 2), range)
    }

    @Test
    fun aWindowSpanningAPieceBoundaryCoversBothPieces() {
        val range = PieceWindowCalculator.piecesFor(0L, pieceLength, 10, byteOffset = 150L, lengthBytes = 100L)

        assertEquals(PieceRange(1, 2), range)
    }

    @Test
    fun aWindowEndingExactlyOnABoundaryDoesNotTouchTheNextPiece() {
        val range = PieceWindowCalculator.piecesFor(0L, pieceLength, 10, byteOffset = 100L, lengthBytes = 100L)

        assertEquals(PieceRange(1, 1), range)
    }

    @Test
    fun byteOffsetZeroStartsAtTheFirstPiece() {
        val range = PieceWindowCalculator.piecesFor(0L, pieceLength, 10, byteOffset = 0L, lengthBytes = 250L)

        assertEquals(PieceRange(0, 2), range)
    }

    @Test
    fun aWindowRunningPastTheLastPieceIsClamped() {
        val range = PieceWindowCalculator.piecesFor(0L, pieceLength, 10, byteOffset = 850L, lengthBytes = 10_000L)

        assertEquals(PieceRange(8, 9), range)
    }

    @Test
    fun aNonZeroFileOffsetShiftsTheWindowToAbsolutePieces() {
        val range = PieceWindowCalculator.piecesFor(fileOffsetInTorrent = 350L, pieceLength, 10, byteOffset = 0L, lengthBytes = 100L)

        assertEquals(PieceRange(3, 4), range)
    }

    @Test
    fun aNonPositiveLengthIsTheSinglePieceContainingTheOffset() {
        assertEquals(PieceRange(4, 4), PieceWindowCalculator.piecesFor(0L, pieceLength, 10, 420L, 0L))
        assertEquals(PieceRange(4, 4), PieceWindowCalculator.piecesFor(0L, pieceLength, 10, 420L, -5L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aNonPositivePieceLengthIsRejected() {
        PieceWindowCalculator.piecesFor(0L, 0, 10, 0L, 1L)
    }
}
