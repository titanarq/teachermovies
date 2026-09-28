package com.teachermovies.http

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
import com.teachermovies.http.bridge.BridgeJobHub
import com.teachermovies.http.bridge.BridgeStream
import com.teachermovies.http.bridge.BridgeStreamEvent
import com.teachermovies.torrent.fake.FakeTorrentEngine
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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
 * The phone's "Buscar subtítulos" routes (#285): a retry that makes every missing language due now
 * and nudges the bridge, the read-only state the web UI follows, and the phone-only auth of both.
 */
class SubtitleSearchRoutesTest {
    private val engine = FakeTorrentEngine()
    private val repo = InMemoryTorrentRepository()
    private val fetches = InMemorySubtitleFetchRepository()
    private val hub = BridgeJobHub()
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
            subtitles = FakeSubtitleStore(),
            library = repo,
            bridge = hub,
            subtitleFetches = fetches,
            allowTestRemoteHeader = true,
        )

    private val movie = TorrentId("a".repeat(40))

    private fun ApplicationTestBuilder.setUp(): HttpClient {
        application { module(deps) }
        return lanClient()
    }

    private suspend fun token(scope: TokenScope = TokenScope.PHONE): String =
        (pairing.pair(pairing.currentPin(), scope) as PairResult.Paired).token

    private suspend fun completedMovie(id: TorrentId = movie) {
        repo.upsert(
            Torrent(
                id = id,
                name = "Movie",
                state = DownloadState.Completed,
                progressPercent = 100.0,
                downloadedBytes = 1_000L,
                totalBytes = 1_000L,
                savePath = "/vol/Movies/${id.value}",
                mainFileIndex = 0,
                errorMessage = null,
            ),
            "/vol/Movies/${id.value}/Movie.mkv",
            now,
        )
    }

    private fun row(
        language: String,
        state: SubtitleFetchState,
        id: TorrentId = movie,
    ): SubtitleFetch {
        val pending = SubtitleFetch.pending(id, language, movieHash = "0123456789abcdef", now = now - 1_000L)
        return when (state) {
            SubtitleFetchState.Pending -> pending
            SubtitleFetchState.Searching -> pending.searchingAt(now - 500L)
            SubtitleFetchState.Downloaded -> pending.downloadedAt(now - 500L, "/vol/subs/x.$language.srt", "latino")
            SubtitleFetchState.NotFound -> pending.searchingAt(now - 900L).notFoundAt(now - 500L)
            SubtitleFetchState.Failed -> pending.searchingAt(now - 900L).failedAt(now - 500L, "quota")
        }
    }

    private suspend fun HttpClient.search(
        token: String?,
        id: String = movie.value,
    ): HttpResponse = post("/api/library/$id/subtitles/search") { if (token != null) bearerAuth(token) }

    private suspend fun HttpClient.state(
        token: String?,
        id: String = movie.value,
    ): HttpResponse = get("/api/library/$id/subtitles") { if (token != null) bearerAuth(token) }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.status(): String = getValue("status").jsonPrimitive.content

    private fun JsonObject.languageStates(): Map<String, String> =
        getValue("languages").jsonArray.associate {
            it.jsonObject
                .getValue("language")
                .jsonPrimitive.content to
                it.jsonObject
                    .getValue("state")
                    .jsonPrimitive.content
        }

    private fun BridgeStream.next(): BridgeStreamEvent? = events.tryReceive().getOrNull()

    @Test
    fun `retry makes not-found and failed rows due now and nudges the bridge for that movie`() =
        testApplication {
            val client = setUp()
            completedMovie()
            fetches.save(row("es", SubtitleFetchState.NotFound))
            fetches.save(row("en", SubtitleFetchState.Failed))
            val stream = hub.connect()
            now += 60_000L

            val response = client.search(token())

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.json()
            assertEquals("searching", body.status())
            assertTrue(body.getValue("laptopConnected").jsonPrimitive.boolean)
            assertEquals(mapOf("en" to "pending", "es" to "pending"), body.languageStates())
            val es = fetches.get(movie, "es")!!
            assertEquals(SubtitleFetchState.Pending, es.state)
            assertNull(es.nextRetryEpochMs)
            assertEquals(1, es.attempts)
            assertEquals("0123456789abcdef", es.movieHash)
            assertEquals(now, es.updatedAtEpochMs)
            assertNull(fetches.get(movie, "en")!!.errorMessage)
            assertEquals(2, fetches.dueForFetch(now).size)
            assertEquals(BridgeStreamEvent.SubtitlesNeeded(movie.value), stream.next())
            assertNull(stream.next())
        }

    @Test
    fun `retry leaves a downloaded row and a search in flight alone`() =
        testApplication {
            val client = setUp()
            completedMovie()
            val downloaded = row("es", SubtitleFetchState.Downloaded)
            val searching = row("en", SubtitleFetchState.Searching)
            fetches.save(downloaded)
            fetches.save(searching)
            hub.connect()

            val body = client.search(token()).json()

            assertEquals("searching", body.status())
            assertEquals(mapOf("en" to "searching", "es" to "downloaded"), body.languageStates())
            assertEquals(downloaded, fetches.get(movie, "es"))
            assertEquals(searching, fetches.get(movie, "en"))
        }

    @Test
    fun `with no laptop connected a stale search becomes pending and the answer says laptop offline`() =
        testApplication {
            val client = setUp()
            completedMovie()
            fetches.save(row("es", SubtitleFetchState.Searching))

            val body = client.search(token()).json()

            assertEquals("laptop_offline", body.status())
            assertFalse(body.getValue("laptopConnected").jsonPrimitive.boolean)
            assertEquals(SubtitleFetchState.Pending, fetches.get(movie, "es")!!.state)
            // Nothing is queued for a bridge that is not there; it reads the needs when it connects.
            assertEquals(listOf(movie), fetches.dueForFetch(now).map { it.torrentId })
        }

    @Test
    fun `state reports found with the latino label once every language is downloaded`() =
        testApplication {
            val client = setUp()
            completedMovie()
            fetches.save(row("es", SubtitleFetchState.Downloaded))

            val response = client.state(token())

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                "{\"torrentId\":\"${movie.value}\",\"status\":\"found\",\"laptopConnected\":false," +
                    "\"languages\":[{\"language\":\"es\",\"state\":\"downloaded\",\"variant\":\"latino\"," +
                    "\"attempts\":0}]}",
                response.bodyAsText(),
            )
            assertFalse("no path on the wire", response.bodyAsText().contains("/vol"))
        }

    @Test
    fun `state reports not found and failed once nothing is pending, and changes nothing`() =
        testApplication {
            val client = setUp()
            completedMovie()
            hub.connect()
            val notFound = row("es", SubtitleFetchState.NotFound)
            fetches.save(notFound)
            fetches.save(row("en", SubtitleFetchState.Downloaded))

            assertEquals("not_found", client.state(token()).json().status())
            assertEquals(notFound, fetches.get(movie, "es"))

            fetches.save(row("es", SubtitleFetchState.Failed))
            assertEquals("failed", client.state(token()).json().status())
        }

    @Test
    fun `a movie with no rows yet is no_needs and a retry still nudges the bridge`() =
        testApplication {
            val client = setUp()
            completedMovie()
            val stream = hub.connect()

            val body = client.search(token()).json()

            assertEquals("no_needs", body.status())
            assertEquals(0, body.getValue("languages").jsonArray.size)
            assertEquals(BridgeStreamEvent.SubtitlesNeeded(movie.value), stream.next())
        }

    @Test
    fun `another movie's rows are neither reported nor retried`() =
        testApplication {
            val client = setUp()
            val other = TorrentId("b".repeat(40))
            completedMovie()
            completedMovie(other)
            val othersRow = row("es", SubtitleFetchState.NotFound, id = other)
            fetches.save(othersRow)

            assertEquals("no_needs", client.search(token()).json().status())
            assertEquals(othersRow, fetches.get(other, "es"))
        }

    @Test
    fun `unknown movie is 404 unknown_torrent and a malformed id is 400 invalid_id`() =
        testApplication {
            val client = setUp()
            val token = token()

            val unknown = client.search(token)
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            assertEquals(
                "unknown_torrent",
                unknown
                    .json()
                    .getValue("error")
                    .jsonPrimitive.content,
            )
            assertEquals(HttpStatusCode.NotFound, client.state(token).status)

            val bad = client.search(token, id = "nope")
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertEquals(
                "invalid_id",
                bad
                    .json()
                    .getValue("error")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `no token or a bridge token is 401 and changes nothing`() =
        testApplication {
            val client = setUp()
            completedMovie()
            val notFound = row("es", SubtitleFetchState.NotFound)
            fetches.save(notFound)
            val stream = hub.connect()

            assertEquals(HttpStatusCode.Unauthorized, client.search(null).status)
            assertEquals(HttpStatusCode.Unauthorized, client.search(token(TokenScope.BRIDGE)).status)
            assertEquals(HttpStatusCode.Unauthorized, client.state(token(TokenScope.BRIDGE)).status)
            assertEquals(notFound, fetches.get(movie, "es"))
            assertNull(stream.next())
        }
}
