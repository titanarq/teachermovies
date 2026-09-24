package com.teachermovies.http

import com.teachermovies.core.model.TorrentId
import com.teachermovies.torrent.api.EngineError
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EngineErrorHttpTest {
    private fun assertMaps(
        error: EngineError,
        status: HttpStatusCode,
        code: String,
    ) {
        val http = error.toHttp()
        assertEquals(status, http.status)
        assertEquals(code, http.body.error)
    }

    @Test
    fun `every engine error maps to its status and code`() {
        assertMaps(EngineError.InvalidMagnet, HttpStatusCode.BadRequest, "invalid_magnet")
        assertMaps(EngineError.InvalidTorrentFile, HttpStatusCode.BadRequest, "invalid_torrent")
        assertMaps(EngineError.UnknownTorrent, HttpStatusCode.NotFound, "unknown_torrent")
        assertMaps(EngineError.AlreadyExists(TorrentId("a".repeat(40))), HttpStatusCode.Conflict, "already_exists")
        assertMaps(EngineError.NotReady, HttpStatusCode.Conflict, "not_ready")
        assertMaps(EngineError.Unsupported, HttpStatusCode.NotImplemented, "unsupported")
        assertMaps(EngineError.Io("disk full at /secret/path"), HttpStatusCode.InternalServerError, "io_error")
    }

    @Test
    fun `already exists carries the existing id, other errors none`() {
        val id = TorrentId("a".repeat(40))

        assertEquals(id.value, EngineError.AlreadyExists(id).toHttp().body.id)
        assertEquals(null, EngineError.UnknownTorrent.toHttp().body.id)
    }

    @Test
    fun `io error never leaks the engine message`() {
        val body = EngineError.Io("disk full at /secret/path").toHttp().body

        assertFalse(body.message.contains("/secret/path"))
    }
}
