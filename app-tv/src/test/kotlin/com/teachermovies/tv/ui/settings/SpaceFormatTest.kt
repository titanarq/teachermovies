package com.teachermovies.tv.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SpaceFormatTest {
    @Test
    fun freeSpaceHasOneDecimalWithComma() {
        assertEquals("5,0", SpaceFormat.freeGb(5_000_000_000))
        assertEquals("5,1", SpaceFormat.freeGb(5_050_000_000))
        assertEquals("0,3", SpaceFormat.freeGb(250_000_000))
    }

    @Test
    fun totalSpaceIsWholeGigabytes() {
        assertEquals("16", SpaceFormat.totalGb(15_600_000_000))
        assertEquals("128", SpaceFormat.totalGb(128_000_000_000))
    }

    @Test
    fun largeValuesHaveNoGroupingSeparator() {
        assertEquals("2000,0", SpaceFormat.freeGb(2_000_000_000_000))
        assertEquals("4000", SpaceFormat.totalGb(4_000_000_000_000))
    }

    @Test
    fun zeroAndNegativeAreZero() {
        assertEquals("0,0", SpaceFormat.freeGb(0))
        assertEquals("0", SpaceFormat.totalGb(-1))
    }
}
