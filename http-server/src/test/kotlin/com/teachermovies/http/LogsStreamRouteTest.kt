package com.teachermovies.http

import com.teachermovies.core.log.LogLevel
import com.teachermovies.core.log.RingBufferLogSink
import com.teachermovies.core.repo.fake.InMemoryTorrentRepository
import com.teachermovies.http.auth.InMemorySettingsRepository
import com.teachermovies.http.auth.PairResult
import com.teachermovies.http.auth.PairingManager
import com.teachermovies.http.auth.TEST_REMOTE_HEADER
import com.teachermovies.http.auth.lanClient
import com.teachermovies.torrent.fake.FakeTorrentEngine
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO as ServerCIO

/**
 * `GET /api/logs/stream` (#269): a `boot` frame, the backlog from `since` as `log` events, then
 * every new line as it is recorded -- filtered by `level`, and never through a `?token=`.
 *
 * The happy paths drive a real `embeddedServer` on a loopback port with a real client engine
 * (`ktor-client-cio`), exactly like [EventsRouteTest]: the in-process test host runs a request's
 * whole pipeline -- including the response body -- to completion before handing anything back to
 * its client, which an SSE stream that outlives the request never does. The 401/400 paths answer
 * before the stream starts, so those use `testApplication`.
 */
class LogsStreamRouteTest {
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
            allowTestRemoteHeader = true, // the loopback client below sends `X-Test-Remote` (#59).
        )

    /** Reads one `event: <name>` / `data: ...` frame off [channel], skipping any `: ping` comments. */
    private suspend fun readEvent(channel: ByteReadChannel): Pair<String, String> =
        withTimeout(5_000) {
            while (true) {
                val eventLine = channel.readUTF8Line() ?: error("stream ended before an event arrived")
                if (eventLine.startsWith(":")) {
                    channel.readUTF8Line() // the blank line after a comment
                    continue
                }
                assertTrue(eventLine.startsWith("event: "))
                val dataLine = channel.readUTF8Line() ?: error("stream ended before a data line arrived")
                assertTrue(dataLine.startsWith("data: "))
                channel.readUTF8Line() // the blank line terminating the frame
                return@withTimeout eventLine.removePrefix("event: ") to dataLine.removePrefix("data: ")
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    @Test
    fun `replays the backlog from since, then streams every new line above the level`() =
        runBlocking {
            logs.recordTestEntry(1L)
            logs.recordTestEntry(2L, level = LogLevel.WARN)
            logs.recordTestEntry(3L, level = LogLevel.ERROR)
            val server = embeddedServer(ServerCIO, port = 0) { module(deps) }.start(wait = false)
            val client = HttpClient(ClientCIO)
            try {
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                val token = (pairing.pair(pairing.currentPin()) as PairResult.Paired).token

                client
                    .prepareGet("http://127.0.0.1:$port/api/logs/stream?since=1&level=warn") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        header(TEST_REMOTE_HEADER, "127.0.0.1")
                    }.execute { response ->
                        assertEquals(HttpStatusCode.OK, response.status)
                        assertTrue(response.contentType()!!.match(ContentType.Text.EventStream))
                        assertEquals("no-cache", response.headers["Cache-Control"])

                        val channel = response.bodyAsChannel()
                        assertEquals("boot" to """{"bootId":"$LOGS_BOOT_ID"}""", readEvent(channel))
                        // since=1 skips seq 1; level=warn would have dropped it anyway.
                        assertEquals("log" to logEntryJson(2L, "warn"), readEvent(channel))
                        assertEquals("log" to logEntryJson(3L, "error"), readEvent(channel))

                        logs.recordTestEntry(4L, level = LogLevel.ERROR, message = "live line")
                        assertEquals("log" to logEntryJson(4L, "error", "live line"), readEvent(channel))

                        // Below the stream's minimum level: recorded, but never sent.
                        logs.recordTestEntry(5L)
                        logs.recordTestEntry(6L, level = LogLevel.ERROR, message = "after")
                        assertEquals("log" to logEntryJson(6L, "error", "after"), readEvent(channel))
                    }
            } finally {
                client.close()
                server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
            }
        }

    @Test
    fun `without parameters, replays the whole buffer and ignores limit`() =
        runBlocking {
            logs.recordTestEntry(1L)
            logs.recordTestEntry(2L, level = LogLevel.WARN)
            val server = embeddedServer(ServerCIO, port = 0) { module(deps) }.start(wait = false)
            val client = HttpClient(ClientCIO)
            try {
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                val token = (pairing.pair(pairing.currentPin()) as PairResult.Paired).token

                client
                    .prepareGet("http://127.0.0.1:$port/api/logs/stream?limit=abc") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        header(TEST_REMOTE_HEADER, "127.0.0.1")
                    }.execute { response ->
                        assertEquals(HttpStatusCode.OK, response.status)

                        val channel = response.bodyAsChannel()
                        assertEquals("boot" to """{"bootId":"$LOGS_BOOT_ID"}""", readEvent(channel))
                        assertEquals("log" to logEntryJson(1L, "info"), readEvent(channel))
                        assertEquals("log" to logEntryJson(2L, "warn"), readEvent(channel))
                    }
            } finally {
                client.close()
                server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
            }
        }

    @Test
    fun `a query token is 401 on the stream, and so are no token and an unknown one`() =
        testApplication {
            application { module(deps) }
            val client = lanClient()
            val token = (pairing.pair(pairing.currentPin()) as PairResult.Paired).token

            // The `?token=` exception of ADR-0002 stays exclusive to `GET /api/events` (#269).
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/logs/stream?token=$token").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/logs/stream").status)

            val unknown =
                client.get("/api/logs/stream") {
                    header(HttpHeaders.Authorization, "Bearer not-a-token")
                }
            assertEquals(HttpStatusCode.Unauthorized, unknown.status)
            assertEquals("Bearer", unknown.headers[HttpHeaders.WWWAuthenticate])
        }

    @Test
    fun `an invalid since or level is 400 before the stream starts`() =
        testApplication {
            application { module(deps) }
            val client = lanClient()
            val token = (pairing.pair(pairing.currentPin()) as PairResult.Paired).token
            val badQueries = listOf("since=abc", "since=-1", "level=trace", "level=")

            for (query in badQueries) {
                val response =
                    client.get("/api/logs/stream?$query") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                assertEquals(query, HttpStatusCode.BadRequest, response.status)
            }
        }
}
