package com.teachermovies.http

import com.teachermovies.http.auth.InMemorySettingsRepository
import com.teachermovies.http.auth.PairingManager
import com.teachermovies.http.auth.lanClient
import com.teachermovies.storage.SpaceInfo
import com.teachermovies.torrent.fake.FakeTorrentEngine
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class StatusRouteTest {
    private val engine = FakeTorrentEngine()

    private fun deps(space: SpaceInfo?) =
        ServerDeps(
            engine = engine,
            space = { space },
            appVersion = "1.2.3",
            clock = { 0L },
            pairing = PairingManager(InMemorySettingsRepository(), SecureRandom(), { 0L }),
            subtitles = FakeSubtitleStore(),
            allowTestRemoteHeader = true,
        )

    @Test
    fun `status reports version, engine, space and torrent count as JSON`() =
        testApplication {
            engine.start()
            engine.addMagnet("magnet:?xt=urn:btih:${"a".repeat(40)}")
            engine.addMagnet("magnet:?xt=urn:btih:${"b".repeat(40)}")
            application { module(deps(SpaceInfo(freeBytes = 123, totalBytes = 456))) }
            val client = lanClient()

            val response = client.get("/api/status")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.contentType()!!.match(ContentType.Application.Json))
            assertEquals(
                """{"version":"1.2.3","engine":"running","freeBytes":123,"totalBytes":456,"torrents":2}""",
                response.bodyAsText(),
            )
        }

    @Test
    fun `status reports null space when unknown and the engine status lower-case`() =
        testApplication {
            application { module(deps(space = null)) }
            val client = lanClient()

            val response = client.get("/api/status")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                """{"version":"1.2.3","engine":"stopped","freeBytes":null,"totalBytes":null,"torrents":0}""",
                response.bodyAsText(),
            )
        }

    @Test
    fun `status needs no token`() =
        testApplication {
            application { module(deps(space = null)) }
            val client = lanClient()

            assertEquals(HttpStatusCode.OK, client.get("/api/status").status)
        }

    @Test
    fun `unknown api route is a 404 JSON error`() =
        testApplication {
            application { module(deps(space = null)) }
            val client = lanClient()

            val response = client.get("/api/unknown")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.contentType()!!.match(ContentType.Application.Json))
            assertEquals("""{"error":"not_found","message":"No such endpoint"}""", response.bodyAsText())
        }
}
