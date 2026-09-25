package com.teachermovies.mobile.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedLinkParserTest {
    private val v1 = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a&dn=Movie"
    private val v2 = "magnet:?xt=urn:btmh:1220d2474e86c95b19b8bcfdb92bc12c9d44667cfa36d2474e86c95b19b8bcfdb92b&dn=Movie"

    @Test
    fun `bare magnet is returned as is`() {
        assertEquals(v1, SharedLinkParser.extractMagnet(v1))
    }

    @Test
    fun `magnet surrounded by other text is cut at the first whitespace`() {
        assertEquals(v1, SharedLinkParser.extractMagnet("Movie title $v1 shared from my browser"))
    }

    @Test
    fun `uppercase scheme is matched`() {
        val upper = "MAGNET:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a"
        assertEquals(upper, SharedLinkParser.extractMagnet("look: $upper"))
    }

    @Test
    fun `bittorrent v2 btmh magnet is accepted`() {
        assertEquals(v2, SharedLinkParser.extractMagnet(v2))
    }

    @Test
    fun `trailing newline is not part of the magnet`() {
        assertEquals(v1, SharedLinkParser.extractMagnet("$v1\n"))
    }

    @Test
    fun `first of two magnets wins`() {
        assertEquals(v1, SharedLinkParser.extractMagnet("$v1 $v2"))
    }

    @Test
    fun `magnet without xt is rejected`() {
        assertNull(SharedLinkParser.extractMagnet("magnet:?dn=Movie&tr=udp%3A%2F%2Ftracker.example%3A80"))
    }

    @Test
    fun `http and https links are rejected`() {
        assertNull(SharedLinkParser.extractMagnet("http://example.com/movie.torrent"))
        assertNull(SharedLinkParser.extractMagnet("https://example.com/?xt=urn:btih:abc"))
    }

    @Test
    fun `blank text is rejected`() {
        assertNull(SharedLinkParser.extractMagnet(""))
        assertNull(SharedLinkParser.extractMagnet("  \n\t"))
    }

    @Test
    fun `null is rejected`() {
        assertNull(SharedLinkParser.extractMagnet(null))
    }
}
