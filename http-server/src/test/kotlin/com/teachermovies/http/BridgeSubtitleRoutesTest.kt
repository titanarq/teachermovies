package com.teachermovies.http

import com.teachermovies.bridge.protocol.BridgeSubtitleProtocol
import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.SubtitleFetch
import com.teachermovies.core.model.SubtitleFetchState
import com.teachermovies.core.model.Torrent
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.fake.InMemorySubtitleFetchRepository
import com.teachermovies.core.repo.fake.InMemoryTorrentRepository
import com.teachermovies.http.auth.InMemorySettingsRepository
import com.teachermovies.http.auth.PairResult
import com.teachermovies.http.auth.PairingManager
import com.teachermovies.http.auth.TokenScope
import com.teachermovies.http.auth.lanClient
import com.teachermovies.torrent.fake.FakeTorrentEngine
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * The bridge's subtitle routes (#280), in-process: the needs list out of the fetch-state rows of
 * #274, an upload named after its own movie, the statuses that move a row on, and the bridge-only
 * auth of all three.
 */
class BridgeSubtitleRoutesTest {
    private val engine = FakeTorrentEngine()
    private val repo = InMemoryTorrentRepository()
    private val fetches = InMemorySubtitleFetchRepository()
    private val subtitles = FakeSubtitleStore()
    private val pairing = PairingManager(InMemorySettingsRepository(), SecureRandom(), { 0L })
    private var now = 1_790_000_000_000L

    private val deps =
        ServerDeps(
            engine = engine,
            remove = engine::remove,
            space = { null },
            appVersion = "test",
            clock = { now },
            pairing = pairing,
            subtitles = subtitles,
            library = repo,
            subtitleFetches = fetches,
            allowTestRemoteHeader = true,
        )

    private fun ApplicationTestBuilder.setUp(): HttpClient {
        application { module(deps) }
        return lanClient()
    }

    private suspend fun token(scope: TokenScope): String =
        (pairing.pair(pairing.currentPin(), scope) as PairResult.Paired).token

    private fun hash(hashChar: Char): String = hashChar.toString().repeat(40)

    private fun id(hashChar: Char): TorrentId = TorrentId(hash(hashChar))

    /** A completed movie in the library whose main file is `/vol/Movies/<c>/<name>.mkv`. */
    private suspend fun completedMovie(
        hashChar: Char,
        name: String,
    ) {
        repo.upsert(
            Torrent(
                id = id(hashChar),
                name = name,
                state = DownloadState.Completed,
                progressPercent = 100.0,
                downloadedBytes = 1_000L,
                totalBytes = 1_000L,
                savePath = "/vol/Movies/$hashChar",
                mainFileIndex = 0,
                errorMessage = null,
            ),
            "/vol/Movies/$hashChar/$name.mkv",
            now,
        )
    }

    private fun statusJson(
        torrentId: String,
        language: String,
        status: String,
        message: String? = null,
    ): String {
        val tail = if (message == null) "" else ",\"message\":\"$message\""
        return "{\"torrentId\":\"$torrentId\",\"language\":\"$language\",\"status\":\"$status\"$tail}"
    }

    private suspend fun HttpClient.needs(token: String): String =
        get("/api/bridge/subtitle-needs") { bearerAuth(token) }.bodyAsText()

    private suspend fun HttpClient.upload(
        torrentId: String?,
        language: String?,
        bytes: ByteArray,
        token: String?,
        variant: String? = null,
        torrentIdField: String = BridgeSubtitleProtocol.TORRENT_ID_FIELD,
    ): HttpResponse =
        submitFormWithBinaryData(
            url = "/api/bridge/subtitles",
            formData =
                formData {
                    if (torrentId != null) append(torrentIdField, torrentId)
                    if (language != null) append(BridgeSubtitleProtocol.LANGUAGE_FIELD, language)
                    if (variant != null) append(BridgeSubtitleProtocol.VARIANT_FIELD, variant)
                    append(
                        BridgeSubtitleProtocol.FILE_FIELD,
                        bytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, "text/plain")
                            append(HttpHeaders.ContentDisposition, "filename=\"from-the-bridge.srt\"")
                        },
                    )
                },
        ) { token?.let { bearerAuth(it) } }

    private suspend fun HttpClient.postStatus(
        body: String,
        token: String?,
    ): HttpResponse =
        post("/api/bridge/subtitle-status") {
            token?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun HttpResponse.errorCode(body: String): String =
        Json
            .parseToJsonElement(body)
            .jsonObject["error"]!!
            .jsonPrimitive.content

    @Test
    fun `the needs list carries the due rows with their movie's title and hash, and no path`() =
        testApplication {
            completedMovie('a', "Heat")
            completedMovie('b', "Ronin")
            fetches.save(SubtitleFetch.pending(id('a'), "es", "0123456789abcdef", now))
            val retried = now - SubtitleFetch.NOT_FOUND_RETRY_MS
            fetches.save(SubtitleFetch.pending(id('a'), "en", "0123456789abcdef", retried).notFoundAt(retried))
            fetches.save(SubtitleFetch.pending(id('b'), "es", null, now).searchingAt(now))
            fetches.save(SubtitleFetch.pending(id('b'), "en", null, now).downloadedAt(now, "/vol/Movies/b/Ronin.srt"))

            val client = setUp()
            val body = client.needs(token(TokenScope.BRIDGE))

            val listed = Json.parseToJsonElement(body).jsonArray
            assertEquals(2, listed.size)
            val first = listed[0].jsonObject
            assertEquals(setOf("torrentId", "title", "language", "movieHash", "state", "attempts"), first.keys)
            assertEquals(hash('a'), first["torrentId"]!!.jsonPrimitive.content)
            assertEquals("Heat", first["title"]!!.jsonPrimitive.content)
            assertEquals("es", first["language"]!!.jsonPrimitive.content)
            assertEquals("0123456789abcdef", first["movieHash"]!!.jsonPrimitive.content)
            assertEquals("pending", first["state"]!!.jsonPrimitive.content)
            assertEquals(0, first["attempts"]!!.jsonPrimitive.int)
            val second = listed[1].jsonObject
            assertEquals("en", second["language"]!!.jsonPrimitive.content)
            assertEquals("not_found", second["state"]!!.jsonPrimitive.content)
            assertFalse("no file system path leaks", body.contains("/vol/"))
        }

    @Test
    fun `a need whose movie is gone is dropped, and a not-found one waits out its seven days`() =
        testApplication {
            completedMovie('a', "Heat")
            // Its movie was deleted, so this row is never published again.
            fetches.save(SubtitleFetch.pending(id('c'), "es", null, now))
            fetches.save(SubtitleFetch.pending(id('a'), "es", null, now).notFoundAt(now))

            val client = setUp()
            val bridgeToken = token(TokenScope.BRIDGE)
            assertEquals("[]", client.needs(bridgeToken))

            now += SubtitleFetch.NOT_FOUND_RETRY_MS
            val due = Json.parseToJsonElement(client.needs(bridgeToken)).jsonArray
            assertEquals(1, due.size)
            assertEquals(hash('a'), due[0].jsonObject["torrentId"]!!.jsonPrimitive.content)
        }

    @Test
    fun `an upload is named after its movie, lands under subs and marks the row downloaded`() =
        testApplication {
            completedMovie('a', "Heat")
            fetches.save(SubtitleFetch.pending(id('a'), "es", "0123456789abcdef", now).searchingAt(now))
            val client = setUp()
            val bridgeToken = token(TokenScope.BRIDGE)
            val srt = "1\n00:00:01,000 --> 00:00:02,000\nHola\n".encodeToByteArray()

            val response = client.upload(hash('a'), "ES", srt, bridgeToken, variant = "latino")

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals("""{"path":"subs/Heat.es.opensubtitles.srt"}""", response.bodyAsText())
            val written = "save(${hash('a')},Heat.es.opensubtitles.srt,${srt.size} bytes)"
            assertEquals(listOf(written), subtitles.recordedCalls)
            val row = fetches.get(id('a'), "es")!!
            assertEquals(SubtitleFetchState.Downloaded, row.state)
            assertEquals("/fake/${hash('a')}/subs/Heat.es.opensubtitles.srt", row.localPath)
            assertEquals("latino", row.variantLabel)
            assertEquals("0123456789abcdef", row.movieHash)
            assertEquals(1, row.attempts)
            assertEquals(now, row.updatedAtEpochMs)
            assertEquals("[]", client.needs(bridgeToken))
        }

    @Test
    fun `an upload for a movie with no row is kept and creates a downloaded one`() =
        testApplication {
            completedMovie('a', "Heat")
            val client = setUp()

            val response = client.upload(hash('a'), "en", "hola".encodeToByteArray(), token(TokenScope.BRIDGE))

            assertEquals(HttpStatusCode.Created, response.status)
            val row = fetches.get(id('a'), "en")!!
            assertEquals(SubtitleFetchState.Downloaded, row.state)
            assertNull(row.movieHash)
            assertNull(row.variantLabel)
            assertEquals(0, row.attempts)
        }

    @Test
    fun `an upload over 2 MiB is 413 and writes nothing, exactly 2 MiB is accepted`() =
        testApplication {
            completedMovie('a', "Heat")
            val client = setUp()
            val bridgeToken = token(TokenScope.BRIDGE)
            val limit = BridgeSubtitleProtocol.MAX_SUBTITLE_BYTES.toInt()

            val rejected = client.upload(hash('a'), "es", ByteArray(limit + 1), bridgeToken)
            assertEquals(HttpStatusCode.PayloadTooLarge, rejected.status)
            assertEquals("too_large", rejected.errorCode(rejected.bodyAsText()))
            assertEquals(emptyList<String>(), subtitles.recordedCalls)
            assertNull(fetches.get(id('a'), "es"))

            val atLimit = client.upload(hash('a'), "es", ByteArray(limit), bridgeToken)
            assertEquals(HttpStatusCode.Created, atLimit.status)
        }

    @Test
    fun `an upload for an unknown movie, a bad id, a bad language or a missing field is refused`() =
        testApplication {
            completedMovie('a', "Heat")
            val client = setUp()
            val bridgeToken = token(TokenScope.BRIDGE)
            val srt = "hola".encodeToByteArray()

            val unknown = client.upload(hash('b'), "es", srt, bridgeToken)
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            assertEquals("unknown_torrent", unknown.errorCode(unknown.bodyAsText()))

            val badId = client.upload("not-a-hash", "es", srt, bridgeToken)
            assertEquals("invalid_id", badId.errorCode(badId.bodyAsText()))

            val badLanguage = client.upload(hash('a'), "spanish", srt, bridgeToken)
            assertEquals(HttpStatusCode.BadRequest, badLanguage.status)
            assertEquals("invalid_language", badLanguage.errorCode(badLanguage.bodyAsText()))

            val noLanguage = client.upload(hash('a'), null, srt, bridgeToken)
            assertEquals("bad_request", noLanguage.errorCode(noLanguage.bodyAsText()))
            val wrongField = client.upload(hash('a'), "es", srt, bridgeToken, torrentIdField = "id")
            assertEquals("bad_request", wrongField.errorCode(wrongField.bodyAsText()))

            assertEquals(emptyList<String>(), subtitles.recordedCalls)
            assertNull(fetches.get(id('a'), "es"))
        }

    @Test
    fun `a status moves its row on and takes it off the needs list while it is in flight`() =
        testApplication {
            completedMovie('a', "Heat")
            completedMovie('b', "Ronin")
            fetches.save(SubtitleFetch.pending(id('a'), "es", null, now))
            fetches.save(SubtitleFetch.pending(id('a'), "en", null, now))
            fetches.save(SubtitleFetch.pending(id('b'), "es", null, now))
            val client = setUp()
            val bridgeToken = token(TokenScope.BRIDGE)

            val searching = client.postStatus(statusJson(hash('a'), "ES", "searching"), bridgeToken)
            assertEquals(HttpStatusCode.NoContent, searching.status)
            val picked = fetches.get(id('a'), "es")!!
            assertEquals(SubtitleFetchState.Searching, picked.state)
            assertEquals(1, picked.attempts)
            assertEquals(now, picked.lastAttemptEpochMs)
            assertEquals(2, Json.parseToJsonElement(client.needs(bridgeToken)).jsonArray.size)

            val notFound = client.postStatus(statusJson(hash('a'), "en", "not_found"), bridgeToken)
            assertEquals(HttpStatusCode.NoContent, notFound.status)
            val empty = fetches.get(id('a'), "en")!!
            assertEquals(SubtitleFetchState.NotFound, empty.state)
            assertEquals(now + SubtitleFetch.NOT_FOUND_RETRY_MS, empty.nextRetryEpochMs)

            val failed = client.postStatus(statusJson(hash('b'), "es", "failed", "daily_cap"), bridgeToken)
            assertEquals(HttpStatusCode.NoContent, failed.status)
            val errored = fetches.get(id('b'), "es")!!
            assertEquals(SubtitleFetchState.Failed, errored.state)
            assertEquals("daily_cap", errored.errorMessage)
        }

    @Test
    fun `a status for a need the TV does not have, or a bad one, changes nothing`() =
        testApplication {
            completedMovie('a', "Heat")
            fetches.save(SubtitleFetch.pending(id('a'), "es", null, now))
            val client = setUp()
            val bridgeToken = token(TokenScope.BRIDGE)

            val unknown = client.postStatus(statusJson(hash('a'), "en", "not_found"), bridgeToken)
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            assertEquals("unknown_need", unknown.errorCode(unknown.bodyAsText()))

            val badId = client.postStatus(statusJson("nope", "es", "failed"), bridgeToken)
            assertEquals("invalid_id", badId.errorCode(badId.bodyAsText()))
            val badLanguage = client.postStatus(statusJson(hash('a'), "spa", "failed"), bridgeToken)
            assertEquals("invalid_language", badLanguage.errorCode(badLanguage.bodyAsText()))
            val badStatus = client.postStatus(statusJson(hash('a'), "es", "found"), bridgeToken)
            assertEquals("bad_request", badStatus.errorCode(badStatus.bodyAsText()))
            for (body in listOf("not json", "{}", "{\"torrentId\":\"${hash('a')}\"}")) {
                assertEquals(body, HttpStatusCode.BadRequest, client.postStatus(body, bridgeToken).status)
            }
            val huge = statusJson(hash('a'), "es", "failed", "x".repeat(BridgeSubtitleProtocol.MAX_STATUS_BYTES))
            val tooLarge = client.postStatus(huge, bridgeToken)
            assertEquals(HttpStatusCode.BadRequest, tooLarge.status)
            assertEquals("too_large", tooLarge.errorCode(tooLarge.bodyAsText()))

            assertEquals(SubtitleFetchState.Pending, fetches.get(id('a'), "es")!!.state)
        }

    @Test
    fun `phone tokens, query tokens and no token are 401 on all three subtitle routes`() =
        testApplication {
            val client = setUp()
            val phoneToken = token(TokenScope.PHONE)
            val bridgeToken = token(TokenScope.BRIDGE)
            val srt = "hola".encodeToByteArray()
            val status = statusJson(hash('a'), "es", "searching")

            val phoneNeeds = client.get("/api/bridge/subtitle-needs") { bearerAuth(phoneToken) }
            assertEquals(HttpStatusCode.Unauthorized, phoneNeeds.status)
            assertEquals("Bearer", phoneNeeds.headers[HttpHeaders.WWWAuthenticate])
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/bridge/subtitle-needs").status)
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/bridge/subtitle-needs?token=$bridgeToken").status,
            )

            assertEquals(HttpStatusCode.Unauthorized, client.upload(hash('a'), "es", srt, phoneToken).status)
            assertEquals(HttpStatusCode.Unauthorized, client.upload(hash('a'), "es", srt, null).status)

            assertEquals(HttpStatusCode.Unauthorized, client.postStatus(status, phoneToken).status)
            assertEquals(HttpStatusCode.Unauthorized, client.postStatus(status, null).status)
            val queryToken = client.post("/api/bridge/subtitle-status?token=$bridgeToken") { setBody(status) }
            assertEquals(HttpStatusCode.Unauthorized, queryToken.status)

            assertTrue(subtitles.recordedCalls.isEmpty())
            assertNull(fetches.get(id('a'), "es"))
        }
}
