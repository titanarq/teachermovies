package com.teachermovies.http

import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.fake.InMemoryTorrentRepository
import com.teachermovies.http.auth.InMemorySettingsRepository
import com.teachermovies.http.auth.PairingManager
import com.teachermovies.http.auth.lanClient
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
import java.security.SecureRandom

/**
 * Covers `POST /api/subtitles` (#61): auth, a successful upload, and each error case, checking
 * what reached [FakeSubtitleStore.recordedCalls].
 */
class SubtitleRouteTest {
    private val engine = FakeTorrentEngine()
    private val settings = InMemorySettingsRepository()
    private val pairing = PairingManager(settings, SecureRandom(), { 0L })
    private val subtitles = FakeSubtitleStore()

    private val deps =
        ServerDeps(
            engine = engine,
            space = { null },
            appVersion = "test",
            clock = { 0L },
            pairing = pairing,
            subtitles = subtitles,
            library = InMemoryTorrentRepository(),
            allowTestRemoteHeader = true,
        )

    private val hashA = "a".repeat(40)
    private val unknownId = "c".repeat(40)
    private val invalidId = "not-an-info-hash"
    private val srtBytes = "1\n00:00:01,000 --> 00:00:02,000\nHello\n".toByteArray()

    /** Runs [block] against the app with an HTTP client and a paired token. */
    private fun withApi(block: suspend ApplicationTestBuilder.(client: HttpClient, token: String) -> Unit) =
        testApplication {
            application { module(deps) }
            val api = lanClient()
            val body =
                api
                    .post("/api/pair") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"pin":"${pairing.currentPin()}"}""")
                    }.bodyAsText()
            val token =
                Json
                    .parseToJsonElement(body)
                    .jsonObject["token"]!!
                    .jsonPrimitive.content
            block(api, token)
        }

    private suspend fun addTorrent(hashChar: Char): TorrentId =
        (engine.addMagnet("magnet:?xt=urn:btih:${hashChar.toString().repeat(40)}") as EngineResult.Ok).value

    private suspend fun HttpClient.uploadSubtitle(
        torrentId: String?,
        fileName: String,
        bytes: ByteArray,
        token: String?,
        torrentIdField: String = SUBTITLE_TORRENT_ID_FIELD,
        fileField: String = SUBTITLE_FILE_FIELD,
    ): HttpResponse =
        submitFormWithBinaryData(
            url = "/api/subtitles",
            formData =
                formData {
                    if (torrentId != null) append(torrentIdField, torrentId)
                    append(
                        fileField,
                        bytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, "text/plain")
                            append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        },
                    )
                },
        ) { token?.let { bearerAuth(it) } }

    private fun HttpResponse.errorCode(body: String): String =
        Json
            .parseToJsonElement(body)
            .jsonObject["error"]!!
            .jsonPrimitive.content

    @Test
    fun `upload saves the file and answers 201 with its path`() =
        withApi { client, token ->
            val id = addTorrent('a')

            val response = client.uploadSubtitle(id.value, "Movie.srt", srtBytes, token)

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals("""{"path":"subs/Movie.srt"}""", response.bodyAsText())
            assertEquals(listOf("save(${id.value},Movie.srt,${srtBytes.size} bytes)"), subtitles.recordedCalls)
        }

    @Test
    fun `upload accepts ass, ssa and vtt too`() =
        withApi { client, token ->
            val id = addTorrent('a')

            for (extension in listOf("ass", "ssa", "vtt")) {
                val response = client.uploadSubtitle(id.value, "Movie.$extension", srtBytes, token)
                assertEquals(HttpStatusCode.Created, response.status)
                assertEquals("""{"path":"subs/Movie.$extension"}""", response.bodyAsText())
            }
        }

    @Test
    fun `upload without a token is 401 and never reaches the store`() =
        withApi { client, _ ->
            val id = addTorrent('a')

            val response = client.uploadSubtitle(id.value, "Movie.srt", srtBytes, token = null)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(subtitles.recordedCalls.isEmpty())
        }

    @Test
    fun `upload for an unknown torrent is 404, an invalid id is 400`() =
        withApi { client, token ->
            val unknown = client.uploadSubtitle(unknownId, "Movie.srt", srtBytes, token)
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            assertEquals("""{"error":"unknown_torrent","message":"No such torrent"}""", unknown.bodyAsText())

            val invalid = client.uploadSubtitle(invalidId, "Movie.srt", srtBytes, token)
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertEquals("invalid_id", invalid.errorCode(invalid.bodyAsText()))

            assertTrue(subtitles.recordedCalls.isEmpty())
        }

    @Test
    fun `upload with an unsupported extension is 400 unsupported_subtitle`() =
        withApi { client, token ->
            val id = addTorrent('a')

            val response = client.uploadSubtitle(id.value, "Movie.mkv", srtBytes, token)

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("unsupported_subtitle", response.errorCode(response.bodyAsText()))
            assertTrue(subtitles.recordedCalls.isEmpty())
        }

    @Test
    fun `upload over 2 MiB is 413 too_large, exactly 2 MiB is accepted`() =
        withApi { client, token ->
            val id = addTorrent('a')

            val tooLarge = ByteArray((MAX_SUBTITLE_FILE_BYTES + 1).toInt()) { 'x'.code.toByte() }
            val rejected = client.uploadSubtitle(id.value, "Movie.srt", tooLarge, token)
            assertEquals(HttpStatusCode.PayloadTooLarge, rejected.status)
            assertEquals("too_large", rejected.errorCode(rejected.bodyAsText()))
            assertTrue(subtitles.recordedCalls.isEmpty())

            val atLimit = ByteArray(MAX_SUBTITLE_FILE_BYTES.toInt()) { 'x'.code.toByte() }
            assertEquals(HttpStatusCode.Created, client.uploadSubtitle(id.value, "Movie.srt", atLimit, token).status)
        }

    @Test
    fun `upload missing the torrentId or file field, or not multipart, is 400 bad_request`() =
        withApi { client, token ->
            val id = addTorrent('a')

            val noTorrentId =
                client.uploadSubtitle(
                    torrentId = null,
                    fileName = "Movie.srt",
                    bytes = srtBytes,
                    token = token,
                )
            assertEquals(HttpStatusCode.BadRequest, noTorrentId.status)
            assertEquals("bad_request", noTorrentId.errorCode(noTorrentId.bodyAsText()))

            val wrongField = client.uploadSubtitle(id.value, "Movie.srt", srtBytes, token, fileField = "subtitle")
            assertEquals(HttpStatusCode.BadRequest, wrongField.status)
            assertEquals("bad_request", wrongField.errorCode(wrongField.bodyAsText()))

            val notMultipart =
                client.post("/api/subtitles") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.BadRequest, notMultipart.status)
            assertEquals("bad_request", notMultipart.errorCode(notMultipart.bodyAsText()))

            assertTrue(subtitles.recordedCalls.isEmpty())
        }

    @Test
    fun `upload maps a store failure to 500 io_error`() =
        withApi { client, token ->
            val id = addTorrent('a')
            subtitles.nextResult = { _, _, _ -> Result.failure(IllegalStateException("disk full")) }

            val response = client.uploadSubtitle(id.value, "Movie.srt", srtBytes, token)

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals("io_error", response.errorCode(response.bodyAsText()))
        }
}
