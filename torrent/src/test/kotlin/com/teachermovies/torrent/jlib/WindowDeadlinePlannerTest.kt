package com.teachermovies.torrent.jlib

import com.teachermovies.torrent.api.PieceRange
import com.teachermovies.torrent.api.PieceWindowCalculator
import com.teachermovies.torrent.api.RangeReadiness
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowDeadlinePlannerTest {
    private val step = 100

    @Test
    fun aFirstWindowGivesEveryPieceAnAscendingDeadlineAndResetsNothing() {
        val plan = WindowDeadlinePlanner.plan(previous = null, current = PieceRange(3, 6), deadlineStepMs = step)

        assertEquals(mapOf(3 to 100, 4 to 200, 5 to 300, 6 to 400), plan.deadlines)
        assertEquals(listOf(3, 4, 5, 6), plan.deadlines.keys.toList())
        assertEquals(emptyList<Int>(), plan.reset)
    }

    @Test
    fun aWindowSlidForwardByOnePieceResetsTheLeaverAndSchedulesTheNewcomer() {
        val plan =
            WindowDeadlinePlanner.plan(
                previous = PieceRange(3, 6),
                current = PieceRange(4, 7),
                deadlineStepMs = step,
            )

        assertEquals(mapOf(7 to 100), plan.deadlines)
        assertEquals(listOf(3), plan.reset)
    }

    @Test
    fun anUnchangedWindowPlansNothing() {
        val plan =
            WindowDeadlinePlanner.plan(
                previous = PieceRange(3, 6),
                current = PieceRange(3, 6),
                deadlineStepMs = step,
            )

        assertEquals(WindowDeadlinePlan(emptyMap(), emptyList()), plan)
    }

    @Test
    fun aDisjointJumpResetsTheOldWindowAscendingAndSchedulesTheNewOne() {
        val plan =
            WindowDeadlinePlanner.plan(
                previous = PieceRange(0, 2),
                current = PieceRange(8, 9),
                deadlineStepMs = 50,
            )

        assertEquals(mapOf(8 to 50, 9 to 100), plan.deadlines)
        assertEquals(listOf(0, 1, 2), plan.reset)
    }

    @Test
    fun aWindowPastTheLastPieceIsClampedByTheCalculator() {
        val current = PieceWindowCalculator.piecesFor(0L, 100, 10, byteOffset = 850L, lengthBytes = 10_000L)

        val plan = WindowDeadlinePlanner.plan(previous = null, current = current, deadlineStepMs = step)

        assertEquals(PieceRange(8, 9), current)
        assertEquals(mapOf(8 to 100, 9 to 200), plan.deadlines)
        assertEquals(emptyList<Int>(), plan.reset)
    }

    @Test
    fun readinessWithEveryPiecePresentIsReadyForTheWholeLength() {
        val readiness =
            readinessFor(
                PieceRange(1, 3),
                100,
                fileOffsetInTorrent = 50L,
                byteOffset = 100L,
                lengthBytes = 200L,
            ) { true }

        assertEquals(RangeReadiness(ready = true, readyBytes = 200L, missingPieces = emptyList()), readiness)
    }

    @Test
    fun readinessWithAHoleInTheMiddleStopsAtTheHole() {
        // Torrent bytes 150..349 are pieces 1..3; piece 2 (bytes 200..299) is missing.
        val readiness =
            readinessFor(PieceRange(1, 3), 100, fileOffsetInTorrent = 50L, byteOffset = 100L, lengthBytes = 200L) {
                it !=
                    2
            }

        assertEquals(RangeReadiness(ready = false, readyBytes = 50L, missingPieces = listOf(2)), readiness)
    }

    @Test
    fun readinessWithNoPiecePresentHasNoReadyBytes() {
        val readiness =
            readinessFor(
                PieceRange(1, 3),
                100,
                fileOffsetInTorrent = 50L,
                byteOffset = 100L,
                lengthBytes = 200L,
            ) { false }

        assertEquals(RangeReadiness(ready = false, readyBytes = 0L, missingPieces = listOf(1, 2, 3)), readiness)
    }
}
