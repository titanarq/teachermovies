package com.teachermovies.mobile.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedTextTest {
    private val magnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a&dn=Movie"
    private val otherData = "content://downloads/movie.torrent"

    @Test
    fun `a shared text is taken from the extra text`() {
        assertEquals(magnet, SharedText.from(SharedText.ACTION_SEND, magnet, null))
    }

    @Test
    fun `a shared text ignores the data string`() {
        assertEquals(magnet, SharedText.from(SharedText.ACTION_SEND, magnet, otherData))
    }

    @Test
    fun `an opened magnet link is taken from the data string`() {
        assertEquals(magnet, SharedText.from(SharedText.ACTION_VIEW, null, magnet))
    }

    @Test
    fun `an opened magnet link ignores the extra text`() {
        assertEquals(magnet, SharedText.from(SharedText.ACTION_VIEW, otherData, magnet))
    }

    @Test
    fun `a foreign action carries nothing`() {
        assertNull(SharedText.from("android.intent.action.MAIN", magnet, magnet))
        assertNull(SharedText.from("", magnet, magnet))
    }

    @Test
    fun `no action at all carries nothing`() {
        assertNull(SharedText.from(null, magnet, magnet))
    }

    @Test
    fun `an action whose extra is missing carries nothing`() {
        assertNull(SharedText.from(SharedText.ACTION_SEND, null, magnet))
        assertNull(SharedText.from(SharedText.ACTION_VIEW, magnet, null))
    }

    @Test
    fun `the text is handed over untrimmed and unfiltered`() {
        val messy = " Mira esta peli\nhttps://example.com/movie.torrent "

        assertEquals(messy, SharedText.from(SharedText.ACTION_SEND, messy, null))
        assertEquals(messy, SharedText.from(SharedText.ACTION_VIEW, null, messy))
    }

    @Test
    fun `the two actions are the platform intent actions`() {
        assertEquals("android.intent.action.SEND", SharedText.ACTION_SEND)
        assertEquals("android.intent.action.VIEW", SharedText.ACTION_VIEW)
    }
}
