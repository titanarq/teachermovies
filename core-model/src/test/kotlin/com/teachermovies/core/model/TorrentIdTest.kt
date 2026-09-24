package com.teachermovies.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TorrentIdTest {
    private val v1InfoHash = "0123456789abcdef0123456789abcdef01234567"
    private val v2InfoHash = "0123456789abcdef".repeat(4)

    @Test
    fun acceptsAV1InfoHash() {
        assertEquals(40, v1InfoHash.length)
        assertEquals(v1InfoHash, TorrentId(v1InfoHash).value)
    }

    @Test
    fun acceptsAV2InfoHash() {
        assertEquals(64, v2InfoHash.length)
        assertEquals(v2InfoHash, TorrentId(v2InfoHash).value)
    }

    @Test
    fun rejectsAnUpperCaseInfoHash() {
        val upperCase = v1InfoHash.uppercase()

        assertThrows(IllegalArgumentException::class.java) { TorrentId(upperCase) }
        assertThrows(IllegalArgumentException::class.java) { TorrentId(v2InfoHash.uppercase()) }
    }

    @Test
    fun rejectsAMixedCaseInfoHash() {
        val mixedCase = v1InfoHash.replaceFirst("abcdef", "Abcdef")

        assertThrows(IllegalArgumentException::class.java) { TorrentId(mixedCase) }
    }

    @Test
    fun rejectsAnInfoHashOfTheWrongLength() {
        val tooShort = v1InfoHash.dropLast(1)
        val tooLong = v1InfoHash + "a"
        val betweenTheTwoLengths = "a".repeat(41)

        assertEquals(39, tooShort.length)
        assertEquals(41, tooLong.length)
        assertThrows(IllegalArgumentException::class.java) { TorrentId(tooShort) }
        assertThrows(IllegalArgumentException::class.java) { TorrentId(tooLong) }
        assertThrows(IllegalArgumentException::class.java) { TorrentId(betweenTheTwoLengths) }
    }

    @Test
    fun rejectsAnInfoHashWithCharactersOutsideHexadecimal() {
        val notHexadecimal = "g" + v1InfoHash.dropLast(1)

        assertThrows(IllegalArgumentException::class.java) { TorrentId(notHexadecimal) }
    }

    @Test
    fun rejectsAnEmptyInfoHash() {
        assertThrows(IllegalArgumentException::class.java) { TorrentId("") }
    }
}
