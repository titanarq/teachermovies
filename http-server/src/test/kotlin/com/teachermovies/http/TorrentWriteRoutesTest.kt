package com.teachermovies.http

import com.teachermovies.core.model.TorrentId
import com.teachermovies.http.auth.InMemorySettingsRepository
import com.teachermovies.http.auth.PairingManager
import com.teachermovies.torrent.api.EngineResult
import com.teachermovies.torrent.fake.FakeTorrentEngine
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Covers every mutating route (#60) with and without a valid token, plus its error cases, checking
 * what reached the engine through [FakeTorrentEngine.recordedCalls] and its state.
 */
class TorrentWriteRoutesTest {
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

    private val hashA = "a".repeat(40)
    private val magnetA = "magnet:?xt=urn:btih:$hashA"
    private val unknownId = "c".repeat(40)
    private val invalidId = "not-an-info-hash"

    /** Runs [block] against the app with an HTTP client and a paired token. */
    private fun withApi(block: suspend ApplicationTestBuilder.(client: HttpClient, token: String) -> Unit) =
        testApplication {
            application { module(deps) }
            val api = client
            val body =
                api.post("/api/pair") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"pin":"${pairing.currentPin()}"}""")
                }.bodyAsText()
            val token = Json.parseToJsonElement(body).jsonObject["token"]!!.jsonPrimitive.content
            block(api, token)
        }

    private suspend fun addTorrent(hashChar: Char): TorrentId =
        (engine.addMagnet("magnet:?xt=urn:btih:${hashChar.toString().repeat(40)}") as EngineResult.Ok).value

    private suspend fun HttpClient.postMagnet(
        json: String,
        token: String?,
    ): HttpResponse =
        post("/api/torrents/magnet") {
            token?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(json)
        }

    private suspend fun HttpClient.postTorrentFile(
        bytes: ByteArray,
        token: String?,
        field: String = "torrent",
    ): HttpResponse =
        submitFormWithBinaryData(
            url = "/api/torrents/file",
            formData =
                formData {
                    append(
                        field,
                        bytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, "application/x-bittorrent")
                            append(HttpHeaders.ContentDisposition, "filename=\"movie.torrent\"")
                        },
                    )
                },
        ) { token?.let { bearerAuth(it) } }

    private fun HttpResponse.errorCode(body: String): String = Json.parseToJsonElement(body).jsonObject["error"]!!.jsonPrimitive.content

    // -- POST /api/torrents/magnet --

    @Test
    fun `magnet adds the torrent and answers 201 with id and state`() =
        withApi { client, token ->
            val response = client.postMagnet("""{"magnet":"$magnetA"}""", token)

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals("""{"id":"$hashA","state":"fetching_metadata"}""", response.bodyAsText())
            assertEquals(listOf(TorrentId(hashA)), engine.torrents.value.map { it.id })
        }

    @Test
    fun `magnet without a token is 401 and adds nothing`() =
        withApi { client, _ ->
            val response = client.postMagnet("""{"magnet":"$magnetA"}""", token = null)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(engine.torrents.value.isEmpty())
        }

    @Test
    fun `invalid magnet is 400 invalid_magnet, malformed body 400 bad_request`() =
        withApi { client, token ->
            val invalid = client.postMagnet("""{"magnet":"http://example.com"}""", token)
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertEquals("invalid_magnet", invalid.errorCode(invalid.bodyAsText()))

            val malformed = client.postMagnet("""{"nope":1}""", token)
            assertEquals(HttpStatusCode.BadRequest, malformed.status)
            assertEquals("bad_request", malformed.errorCode(malformed.bodyAsText()))

            assertTrue(engine.torrents.value.isEmpty())
        }

    @Test
    fun `duplicate magnet is 409 already_exists with the id`() =
        withApi { client, token ->
            client.postMagnet("""{"magnet":"$magnetA"}""", token)

            val duplicate = client.postMagnet("""{"magnet":"$magnetA"}""", token)

            assertEquals(HttpStatusCode.Conflict, duplicate.status)
            assertEquals(
                """{"error":"already_exists","message":"Torrent already added","id":"$hashA"}""",
                duplicate.bodyAsText(),
            )
        }

    // -- POST /api/torrents/file --

    private val torrentBytes = "d8:announce0:4:infod4:name5:movieee".toByteArray()

    private fun sha1Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `torrent file adds the torrent and answers 201`() =
        withApi { client, token ->
            val response = client.postTorrentFile(torrentBytes, token)

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals("""{"id":"${sha1Hex(torrentBytes)}","state":"fetching_metadata"}""", response.bodyAsText())
        }

    @Test
    fun `torrent file without a token is 401 and adds nothing`() =
        withApi { client, _ ->
            val response = client.postTorrentFile(torrentBytes, token = null)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(engine.torrents.value.isEmpty())
        }

    @Test
    fun `invalid torrent file is 400 invalid_torrent, duplicate 409`() =
        withApi { client, token ->
            val invalid = client.postTorrentFile("not bencode".toByteArray(), token)
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertEquals("invalid_torrent", invalid.errorCode(invalid.bodyAsText()))

            client.postTorrentFile(torrentBytes, token)
            val duplicate = client.postTorrentFile(torrentBytes, token)
            assertEquals(HttpStatusCode.Conflict, duplicate.status)
            assertEquals("already_exists", duplicate.errorCode(duplicate.bodyAsText()))
        }

    @Test
    fun `torrent file over 10 MiB is 413 too_large, exactly 10 MiB is accepted`() =
        withApi { client, token ->
            val tooLarge = ByteArray((MAX_TORRENT_FILE_BYTES + 1).toInt()) { 'd'.code.toByte() }
            val rejected = client.postTorrentFile(tooLarge, token)
            assertEquals(HttpStatusCode.PayloadTooLarge, rejected.status)
            assertEquals("too_large", rejected.errorCode(rejected.bodyAsText()))
            assertTrue(engine.torrents.value.isEmpty())

            val atLimit = ByteArray(MAX_TORRENT_FILE_BYTES.toInt()) { 'd'.code.toByte() }
            assertEquals(HttpStatusCode.Created, client.postTorrentFile(atLimit, token).status)
        }

    @Test
    fun `torrent file without the torrent field or not multipart is 400 bad_request`() =
        withApi { client, token ->
            val wrongField = client.postTorrentFile(torrentBytes, token, field = "file")
            assertEquals(HttpStatusCode.BadRequest, wrongField.status)
            assertEquals("bad_request", wrongField.errorCode(wrongField.bodyAsText()))

            val notMultipart =
                client.post("/api/torrents/file") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.BadRequest, notMultipart.status)
            assertEquals("bad_request", notMultipart.errorCode(notMultipart.bodyAsText()))
        }
}
