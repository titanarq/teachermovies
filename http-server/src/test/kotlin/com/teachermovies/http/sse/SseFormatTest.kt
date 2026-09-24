package com.teachermovies.http.sse

import org.junit.Assert.assertEquals
import org.junit.Test

class SseFormatTest {
    @Test
    fun `single-line data becomes one data line, ending with the terminating blank line`() {
        assertEquals("event: torrents\ndata: []\n\n", SseFormat.event("torrents", "[]"))
    }

    @Test
    fun `multi-line data is split into several data lines`() {
        val data = "line one\nline two\nline three"

        assertEquals(
            "event: torrents\ndata: line one\ndata: line two\ndata: line three\n\n",
            SseFormat.event("torrents", data),
        )
    }

    @Test
    fun `ping is a comment line clients ignore`() {
        assertEquals(": ping\n\n", SseFormat.PING)
    }
}
