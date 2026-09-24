package com.teachermovies.http

import com.teachermovies.http.auth.InMemorySettingsRepository
import com.teachermovies.http.auth.PairingManager
import com.teachermovies.torrent.api.EngineResult
import com.teachermovies.torrent.fake.FakeTorrentEngine
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Covers all three read routes (#57): a valid token before/after metadata, and 401 without one.
 */
class TorrentReadRoutesTest {
    private val engine = FakeTorrentEngine()
    private val settings = InMemorySettingsRepository()
    private val pairing = PairingManager(settings, SecureRandom(), { 0L })

    private val deps =
        ServerDeps(
            engine = engine,
            space = { null },
            appVersion = "test",
            clock = { 0L },
            pairing = pairing,
        )

    private fun ApplicationTestBuilder.setUp() = application { module(deps) }

    private suspend fun HttpClient.pairedToken(): String {
        val body =
            post("/api/pair") {
                contentType(ContentType.Application.Json)
                setBody("""{"pin":"${pairing.currentPin()}"}""")
            }.bodyAsText()
        return Json.parseToJsonElement(body).jsonObject["token"]!!.jsonPrimitive.content
    }

    private suspend fun addTorrent(magnetByte: Char): com.teachermovies.core.model.TorrentId {
        val result = engine.addMagnet("magnet:?xt=urn:btih:${magnetByte.toString().repeat(40)}")
        return (result as EngineResult.Ok).value
    }

    private val unknownId = "c".repeat(40)
    private val invalidId = "not-an-info-hash"

    @Test
    fun `list is empty with no torrents, and needs a token`() =
        testApplication {
            setUp()
            val token = client.pairedToken()

            val ok = client.get("/api/torrents") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, ok.status)
            assertTrue(ok.contentType()!!.match(ContentType.Application.Json))
            assertEquals("[]", ok.bodyAsText())

            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/torrents").status)
        }

    @Test
    fun `list reports every torrent, before and after metadata`() =
        testApplication {
            setUp()
            val token = client.pairedToken()
            val id = addTorrent('a')
            engine.emitMetadata(id, "Movie", listOf("Movie.mkv" to 100L))
            engine.advance(id, 50L, rateBps = 10L, peers = 3)
            addTorrent('b')

            val response = client.get("/api/torrents") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status)
            val array = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(2, array.size)
            val first = array.first { it.jsonObject["id"]!!.jsonPrimitive.content == id.value }
            assertEquals("downloading", first.jsonObject["state"]!!.jsonPrimitive.content)
            assertEquals(50.0, first.jsonObject["progress"]!!.jsonPrimitive.content.toDouble(), 0.0)
            assertEquals(3, first.jsonObject["peers"]!!.jsonPrimitive.content.toInt())
        }

    @Test
    fun `single torrent 200s before and after metadata, 404s unknown, 400s invalid, 401s without a token`() =
        testApplication {
            setUp()
            val token = client.pairedToken()
            val id = addTorrent('a')

            val beforeMetadata = client.get("/api/torrents/${id.value}") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, beforeMetadata.status)
            assertEquals(
                "fetching_metadata",
                Json.parseToJsonElement(beforeMetadata.bodyAsText()).jsonObject["state"]!!.jsonPrimitive.content,
            )

            engine.emitMetadata(id, "Movie", listOf("Movie.mkv" to 100L))
            val afterMetadata = client.get("/api/torrents/${id.value}") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, afterMetadata.status)
            assertEquals(
                "downloading",
                Json.parseToJsonElement(afterMetadata.bodyAsText()).jsonObject["state"]!!.jsonPrimitive.content,
            )

            val unknown = client.get("/api/torrents/$unknownId") { bearerAuth(token) }
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            assertEquals("""{"error":"unknown_torrent","message":"No such torrent"}""", unknown.bodyAsText())

            val invalid = client.get("/api/torrents/$invalidId") { bearerAuth(token) }
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertEquals("""{"error":"invalid_id","message":"Not a valid torrent id"}""", invalid.bodyAsText())

            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/torrents/${id.value}").status)
        }

    @Test
    fun `files is 409 before metadata, 200 after, 404 unknown, 400 invalid, 401 without a token`() =
        testApplication {
            setUp()
            val token = client.pairedToken()
            val id = addTorrent('a')

            val beforeMetadata: HttpResponse = client.get("/api/torrents/${id.value}/files") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Conflict, beforeMetadata.status)
            assertEquals(
                """{"error":"not_ready","message":"Torrent metadata not available yet"}""",
                beforeMetadata.bodyAsText(),
            )

            engine.emitMetadata(id, "Movie", listOf("Movie.mkv" to 100L, "sample.mp4" to 10L))
            val afterMetadata = client.get("/api/torrents/${id.value}/files") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, afterMetadata.status)
            val files = Json.parseToJsonElement(afterMetadata.bodyAsText()).jsonArray
            assertEquals(2, files.size)
            assertEquals("Movie.mkv", files[0].jsonObject["path"]!!.jsonPrimitive.content)
            assertEquals(100L, files[0].jsonObject["size"]!!.jsonPrimitive.content.toLong())
            assertEquals("normal", files[0].jsonObject["priority"]!!.jsonPrimitive.content)

            val unknown = client.get("/api/torrents/$unknownId/files") { bearerAuth(token) }
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            assertEquals("""{"error":"unknown_torrent","message":"No such torrent"}""", unknown.bodyAsText())

            val invalid = client.get("/api/torrents/$invalidId/files") { bearerAuth(token) }
            assertEquals(HttpStatusCode.BadRequest, invalid.status)

            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/torrents/${id.value}/files").status)
        }
}
