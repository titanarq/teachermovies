package com.teachermovies.torrent.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MagnetUriTest {
    private val hexHash = "0123456789abcdef0123456789abcdef01234567"
    private val base32OfHexHash = "AERUKZ4JVPG66AJDIVTYTK6N54ASGRLH"

    @Test
    fun parsesA40HexInfoHashWithDisplayName() {
        val magnet = MagnetUri.parse("magnet:?xt=urn:btih:$hexHash&dn=Movie+Name")

        assertEquals(MagnetUri(hexHash, "Movie Name"), magnet)
    }

    @Test
    fun lowerCasesAnUpperCase40HexInfoHash() {
        val magnet = MagnetUri.parse("magnet:?xt=urn:btih:${hexHash.uppercase()}&dn=Movie")

        assertEquals(hexHash, magnet?.infoHash)
    }

    @Test
    fun convertsA32Base32InfoHashToLowerCaseHex() {
        val magnet = MagnetUri.parse("magnet:?xt=urn:btih:$base32OfHexHash&dn=Movie")

        assertEquals(hexHash, magnet?.infoHash)
    }

    @Test
    fun acceptsALowerCaseBase32InfoHashToo() {
        val magnet = MagnetUri.parse("magnet:?xt=urn:btih:${base32OfHexHash.lowercase()}")

        assertEquals(hexHash, magnet?.infoHash)
    }

    @Test
    fun parsesWithoutADisplayName() {
        val magnet = MagnetUri.parse("magnet:?xt=urn:btih:$hexHash")

        assertEquals(hexHash, magnet?.infoHash)
        assertNull(magnet?.displayName)
    }

    @Test
    fun rejectsAUriWithoutTheMagnetScheme() {
        assertNull(MagnetUri.parse("https://example.com?xt=urn:btih:$hexHash"))
    }

    @Test
    fun rejectsAUriWithNoQuery() {
        assertNull(MagnetUri.parse("magnet:"))
    }

    @Test
    fun rejectsAUriWithNoXtParameter() {
        assertNull(MagnetUri.parse("magnet:?dn=Movie"))
    }

    @Test
    fun rejectsAnInfoHashOfTheWrongLength() {
        assertNull(MagnetUri.parse("magnet:?xt=urn:btih:${hexHash.dropLast(1)}"))
    }

    @Test
    fun rejectsAnInfoHashWithInvalidCharacters() {
        assertNull(MagnetUri.parse("magnet:?xt=urn:btih:" + "g".repeat(40)))
    }

    @Test
    fun rejectsAnEmptyUri() {
        assertNull(MagnetUri.parse(""))
    }
}
