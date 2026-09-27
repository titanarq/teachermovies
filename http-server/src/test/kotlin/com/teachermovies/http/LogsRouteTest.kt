package com.teachermovies.http

import com.teachermovies.core.log.LogLevel
import com.teachermovies.core.log.RingBufferLogSink
import com.teachermovies.core.repo.fake.InMemoryTorrentRepository
import com.teachermovies.http.auth.InMemorySettingsRepository
import com.teachermovies.http.auth.PairResult
import com.teachermovies.http.auth.PairingManager
import com.teachermovies.http.auth.TEST_REMOTE_HEADER
import com.teachermovies.http.auth.TokenScope
import com.teachermovies.http.auth.lanClient
import com.teachermovies.torrent.fake.FakeTorrentEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.SecureRandom

/**
 * `GET /api/logs` (#269): pages the ring buffer with the `since`/`level`/`limit` query, serves
 * phone and bridge tokens alike, and answers 401 -- never a log line -- to anything else, a
 * `?token=` query included (the ADR-0002 exception stays exclusive to `GET /api/events`).
 */
class LogsRouteTest {
    private val engine = FakeTorrentEngine()
    private val settings = InMemorySettingsRepository()
    private val pairing = PairingManager(settings, SecureRandom(), { 0L })
    private val logs = RingBufferLogSink(bootId = LOGS_BOOT_ID)

    private val deps =
        ServerDeps(
            engine = engine,
            remove = engine::remove,
            space = { null },
            appVersion = "test",
            clock = { 0L },
            pairing = pairing,
            subtitles = FakeSubtitleStore(),
            library = InMemoryTorrentRepository(),
            logs = logs,
            allowTestRemoteHeader = true, // the lanClient below sends `X-Test-Remote` (#59).
        )

    /** A [lanClient] whose every request already carries `Bearer [token]` (#59, ADR-0002). */
    private fun ApplicationTestBuilder.authClient(token: String): HttpClient =
        createClient {
            defaultRequest {
                header(TEST_REMOTE_HEADER, "127.0.0.1")
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }

    private suspend fun phoneToken(): String = (pairing.pair(pairing.currentPin()) as PairResult.Paired).token

    private suspend fun bridgeToken(): String =
        (pairing.pair(pairing.currentPin(), TokenScope.BRIDGE) as PairResult.Paired).token

    private fun pageSeqs(body: String): List<Long> =
        Json
            .parseToJsonElement(body)
            .jsonObject["entries"]!!
            .jsonArray
            .map { it.jsonObject["seq"]!!.jsonPrimitive.long }

    @Test
    fun `serves the buffered page oldest first, with the boot id and lower-case levels`() =
        testApplication {
            application { module(deps) }
            logs.recordTestEntry(1L)
            logs.recordTestEntry(2L, level = LogLevel.WARN, message = "engine stalled")
            logs.recordTestEntry(3L, level = LogLevel.ERROR, message = "engine failed")

            val response = authClient(phoneToken()).get("/api/logs")

            assertEquals(HttpStatusCode.OK, response.status)
            val expected =
                """{"bootId":"$LOGS_BOOT_ID","entries":[""" +
                    "${logEntryJson(1L, "info")},${logEntryJson(2L, "warn", "engine stalled")}," +
                    "${logEntryJson(3L, "error", "engine failed")}]}"
            assertEquals(expected, response.bodyAsText())
        }

    @Test
    fun `since keeps only the lines after the cursor`() =
        testApplication {
            application { module(deps) }
            logs.recordTestEntry(1L)
            logs.recordTestEntry(2L)
            logs.recordTestEntry(3L)

            val response = authClient(phoneToken()).get("/api/logs?since=2")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf(3L), pageSeqs(response.bodyAsText()))
        }

    @Test
    fun `level keeps that level and above`() =
        testApplication {
            application { module(deps) }
            logs.recordTestEntry(1L, level = LogLevel.DEBUG)
            logs.recordTestEntry(2L, level = LogLevel.INFO)
            logs.recordTestEntry(3L, level = LogLevel.WARN)
            logs.recordTestEntry(4L, level = LogLevel.ERROR)

            val response = authClient(phoneToken()).get("/api/logs?level=warn")

            assertEquals(listOf(3L, 4L), pageSeqs(response.bodyAsText()))
        }

    @Test
    fun `limit caps the page at the oldest lines`() =
        testApplication {
            application { module(deps) }
            logs.recordTestEntry(1L)
            logs.recordTestEntry(2L)
            logs.recordTestEntry(3L)

            val response = authClient(phoneToken()).get("/api/logs?limit=2")

            assertEquals(listOf(1L, 2L), pageSeqs(response.bodyAsText()))
        }

    @Test
    fun `a bridge token reads the logs too`() =
        testApplication {
            application { module(deps) }
            logs.recordTestEntry(1L)

            val response = authClient(bridgeToken()).get("/api/logs")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf(1L), pageSeqs(response.bodyAsText()))
        }

    @Test
    fun `an invalid parameter is 400 bad_request`() =
        testApplication {
            application { module(deps) }
            val client = authClient(phoneToken())
            val badQueries =
                listOf(
                    "since=abc",
                    "since=-1",
                    "since=1.5",
                    "level=trace",
                    "level=",
                    "limit=0",
                    "limit=5001",
                    "limit=abc",
                )

            for (query in badQueries) {
                val response = client.get("/api/logs?$query")
                assertEquals(query, HttpStatusCode.BadRequest, response.status)
                val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]
                assertEquals(query, "bad_request", error!!.jsonPrimitive.content)
            }
        }

    @Test
    fun `401 without a token, with an unknown one, and with a query token`() =
        testApplication {
            application { module(deps) }
            logs.recordTestEntry(1L)
            val client = lanClient()
            val token = phoneToken()

            val noToken = client.get("/api/logs")
            assertEquals(HttpStatusCode.Unauthorized, noToken.status)
            assertEquals("Bearer", noToken.headers[HttpHeaders.WWWAuthenticate])

            val unknown =
                client.get("/api/logs") {
                    header(HttpHeaders.Authorization, "Bearer not-a-token")
                }
            assertEquals(HttpStatusCode.Unauthorized, unknown.status)

            // The `?token=` exception of ADR-0002 stays exclusive to `GET /api/events` (#269).
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/logs?token=$token").status)
        }
}
