package com.teachermovies.http

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.TorrentId
import com.teachermovies.http.auth.InMemorySettingsRepository
import com.teachermovies.http.auth.PairingManager
import com.teachermovies.http.auth.lanClient
import com.teachermovies.torrent.api.EngineResult
import com.teachermovies.torrent.api.FilePriority
import com.teachermovies.torrent.fake.FakeTorrentEngine
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.post
import io.ktor.client.request.put
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
            allowTestRemoteHeader = true,
        )

    private val hashA = "a".repeat(40)
    private val magnetA = "magnet:?xt=urn:btih:$hashA"
    private val unknownId = "c".repeat(40)
    private val invalidId = "not-an-info-hash"

    /** Runs [block] against the app with an HTTP client and a paired token. */
    private fun withApi(block: suspend ApplicationTestBuilder.(client: HttpClient, token: String) -> Unit) =
        testApplication {
            application { module(deps) }
            val api = lanClient()
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

    // -- POST /api/torrents/{id}/pause, /resume --

    @Test
    fun `pause and resume answer 204 and reach the engine`() =
        withApi { client, token ->
            val id = addTorrent('a')
            engine.emitMetadata(id, "Movie", listOf("Movie.mkv" to 100L))

            val paused = client.post("/api/torrents/${id.value}/pause") { bearerAuth(token) }
            assertEquals(HttpStatusCode.NoContent, paused.status)
            assertEquals(DownloadState.Paused, engine.torrents.value.single().state)

            val resumed = client.post("/api/torrents/${id.value}/resume") { bearerAuth(token) }
            assertEquals(HttpStatusCode.NoContent, resumed.status)
            assertEquals(DownloadState.Downloading, engine.torrents.value.single().state)

            assertEquals(listOf("pause(${id.value})", "resume(${id.value})"), engine.recordedCalls)
        }

    @Test
    fun `pause and resume without a token are 401 and never reach the engine`() =
        withApi { client, _ ->
            val id = addTorrent('a')

            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/torrents/${id.value}/pause").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/torrents/${id.value}/resume").status)
            assertTrue(engine.recordedCalls.isEmpty())
        }

    @Test
    fun `pause and resume of an unknown id are 404, of an invalid id 400`() =
        withApi { client, token ->
            for (action in listOf("pause", "resume")) {
                val unknown = client.post("/api/torrents/$unknownId/$action") { bearerAuth(token) }
                assertEquals(HttpStatusCode.NotFound, unknown.status)
                assertEquals("""{"error":"unknown_torrent","message":"No such torrent"}""", unknown.bodyAsText())

                val invalid = client.post("/api/torrents/$invalidId/$action") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, invalid.status)
                assertEquals("invalid_id", invalid.errorCode(invalid.bodyAsText()))
            }
        }

    // -- DELETE /api/torrents/{id} --

    @Test
    fun `delete defaults to keeping files and honours deleteFiles=true`() =
        withApi { client, token ->
            val a = addTorrent('a')
            val b = addTorrent('b')

            assertEquals(HttpStatusCode.NoContent, client.delete("/api/torrents/${a.value}") { bearerAuth(token) }.status)
            assertEquals(
                HttpStatusCode.NoContent,
                client.delete("/api/torrents/${b.value}?deleteFiles=true") { bearerAuth(token) }.status,
            )

            assertEquals(listOf("remove(${a.value},false)", "remove(${b.value},true)"), engine.recordedCalls)
            assertTrue(engine.torrents.value.isEmpty())
        }

    @Test
    fun `delete without a token is 401 and removes nothing`() =
        withApi { client, _ ->
            val id = addTorrent('a')

            assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/torrents/${id.value}?deleteFiles=true").status)
            assertTrue(engine.recordedCalls.isEmpty())
            assertEquals(1, engine.torrents.value.size)
        }

    @Test
    fun `delete of an unknown id is 404, invalid id or deleteFiles 400`() =
        withApi { client, token ->
            val unknown = client.delete("/api/torrents/$unknownId") { bearerAuth(token) }
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            assertEquals("unknown_torrent", unknown.errorCode(unknown.bodyAsText()))

            val invalid = client.delete("/api/torrents/$invalidId") { bearerAuth(token) }
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertEquals("invalid_id", invalid.errorCode(invalid.bodyAsText()))

            val id = addTorrent('a')
            val badFlag = client.delete("/api/torrents/${id.value}?deleteFiles=yes") { bearerAuth(token) }
            assertEquals(HttpStatusCode.BadRequest, badFlag.status)
            assertEquals("bad_request", badFlag.errorCode(badFlag.bodyAsText()))
            assertEquals(1, engine.torrents.value.size)
        }

    // -- PUT /api/torrents/{id}/files --

    private suspend fun HttpClient.putFiles(
        id: String,
        json: String,
        token: String?,
    ): HttpResponse =
        put("/api/torrents/$id/files") {
            token?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(json)
        }

    @Test
    fun `set file priorities answers 204 and reaches the engine`() =
        withApi { client, token ->
            val id = addTorrent('a')
            engine.emitMetadata(id, "Movie", listOf("Movie.mkv" to 100L, "extras.mkv" to 50L))

            val response = client.putFiles(id.value, """[{"index":0,"priority":"high"},{"index":1,"priority":"skip"}]""", token)

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals(listOf("setFilePriorities(${id.value},{0=High, 1=Skip})"), engine.recordedCalls)
            val files = (engine.files(id) as EngineResult.Ok).value
            assertEquals(listOf(FilePriority.High, FilePriority.Skip), files.map { it.priority })
        }

    @Test
    fun `set file priorities without a token is 401 and never reaches the engine`() =
        withApi { client, _ ->
            val id = addTorrent('a')
            engine.emitMetadata(id, "Movie", listOf("Movie.mkv" to 100L))

            assertEquals(HttpStatusCode.Unauthorized, client.putFiles(id.value, """[{"index":0,"priority":"skip"}]""", null).status)
            assertTrue(engine.recordedCalls.isEmpty())
        }

    @Test
    fun `set file priorities is 409 not_ready before metadata`() =
        withApi { client, token ->
            val id = addTorrent('a')

            val response = client.putFiles(id.value, """[{"index":0,"priority":"skip"}]""", token)

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("not_ready", response.errorCode(response.bodyAsText()))
            assertTrue(engine.recordedCalls.isEmpty())
        }

    @Test
    fun `set file priorities rejects unknown priority, unknown index and malformed bodies with 400`() =
        withApi { client, token ->
            val id = addTorrent('a')
            engine.emitMetadata(id, "Movie", listOf("Movie.mkv" to 100L))

            val badPriority = client.putFiles(id.value, """[{"index":0,"priority":"urgent"}]""", token)
            assertEquals(HttpStatusCode.BadRequest, badPriority.status)
            assertEquals("invalid_priority", badPriority.errorCode(badPriority.bodyAsText()))

            val badIndex = client.putFiles(id.value, """[{"index":0,"priority":"high"},{"index":7,"priority":"skip"}]""", token)
            assertEquals(HttpStatusCode.BadRequest, badIndex.status)
            assertEquals("invalid_index", badIndex.errorCode(badIndex.bodyAsText()))

            val malformed = client.putFiles(id.value, """{"index":0}""", token)
            assertEquals(HttpStatusCode.BadRequest, malformed.status)
            assertEquals("bad_request", malformed.errorCode(malformed.bodyAsText()))

            assertTrue("a rejected request applies no change at all", engine.recordedCalls.isEmpty())
        }

    @Test
    fun `set file priorities of an unknown id is 404, invalid id 400`() =
        withApi { client, token ->
            val unknown = client.putFiles(unknownId, """[{"index":0,"priority":"skip"}]""", token)
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            assertEquals("unknown_torrent", unknown.errorCode(unknown.bodyAsText()))

            val invalid = client.putFiles(invalidId, """[{"index":0,"priority":"skip"}]""", token)
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertEquals("invalid_id", invalid.errorCode(invalid.bodyAsText()))
        }
}
