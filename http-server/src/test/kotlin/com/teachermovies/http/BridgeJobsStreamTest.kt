package com.teachermovies.http

import com.teachermovies.core.repo.fake.InMemoryTorrentRepository
import com.teachermovies.http.auth.InMemorySettingsRepository
import com.teachermovies.http.auth.PairResult
import com.teachermovies.http.auth.PairingManager
import com.teachermovies.http.auth.TEST_REMOTE_HEADER
import com.teachermovies.http.auth.TokenScope
import com.teachermovies.http.bridge.BridgeJob
import com.teachermovies.http.bridge.BridgeJobHub
import com.teachermovies.http.bridge.BridgeOutcome
import com.teachermovies.torrent.fake.FakeTorrentEngine
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO as ServerCIO

/**
 * `GET /api/bridge/jobs` (#275) over a real loopback connection, like [EventsRouteTest]: the
 * in-process test host never hands back a response that outlives its request.
 */
class BridgeJobsStreamTest {
    private val engine = FakeTorrentEngine()
    private val pairing = PairingManager(InMemorySettingsRepository(), SecureRandom(), { 0L })
    private var nextId = 0
    private val hub = BridgeJobHub { "job-${++nextId}" }

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
            bridge = hub,
            allowTestRemoteHeader = true,
        )

    /** Reads one `event:`/`data:` frame off [channel], skipping `: ping` comments; null once the stream ends. */
    private suspend fun readEvent(channel: ByteReadChannel): Pair<String, String>? =
        withTimeout(5_000) {
            while (true) {
                val eventLine = channel.readUTF8Line() ?: return@withTimeout null
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
            null
        }

    private fun <T> withServer(block: suspend CoroutineScope.(port: Int, client: HttpClient, token: String) -> T): T =
        runBlocking {
            val server: EmbeddedServer<*, *> = embeddedServer(ServerCIO, port = 0) { module(deps) }.start(wait = false)
            val client = HttpClient(ClientCIO)
            try {
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                val token = (pairing.pair(pairing.currentPin(), TokenScope.BRIDGE) as PairResult.Paired).token
                block(port, client, token)
            } finally {
                client.close()
                server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
            }
        }

    @Test
    fun `a job reaches the bridge, its result resolves submit, and a replaced job is cancelled`() =
        withServer { port, client, token ->
            client
                .prepareGet("http://127.0.0.1:$port/api/bridge/jobs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(TEST_REMOTE_HEADER, "127.0.0.1")
                }.execute { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertTrue(response.contentType()!!.match(ContentType.Text.EventStream))
                    assertEquals("no-cache", response.headers["Cache-Control"])
                    val channel = response.bodyAsChannel()
                    withTimeout(5_000) { hub.connected.first { it } }

                    val first = async { hub.submit(BridgeJob.Translate("one"), 30.seconds) }
                    assertEquals("job" to """{"kind":"translate","id":"job-1","line":"one"}""", readEvent(channel))
                    val second = async { hub.submit(BridgeJob.Translate("two"), 30.seconds) }
                    assertEquals(BridgeOutcome.Replaced, first.await())
                    assertEquals("cancel" to """{"id":"job-1"}""", readEvent(channel))
                    assertEquals("job" to """{"kind":"translate","id":"job-2","line":"two"}""", readEvent(channel))

                    val posted =
                        client.post("http://127.0.0.1:$port/api/bridge/jobs/job-2/result") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                            header(TEST_REMOTE_HEADER, "127.0.0.1")
                            contentType(ContentType.Application.Json)
                            setBody("""{"status":"ok","text":"dos"}""")
                        }
                    assertEquals(HttpStatusCode.NoContent, posted.status)
                    assertEquals(BridgeOutcome.Done("dos"), second.await())
                }
        }

    @Test
    fun `a newer bridge stream closes the older one and inherits connected`() =
        withServer { port, client, token ->
            val second = (pairing.pair(pairing.currentPin(), TokenScope.BRIDGE) as PairResult.Paired).token
            client
                .prepareGet("http://127.0.0.1:$port/api/bridge/jobs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(TEST_REMOTE_HEADER, "127.0.0.1")
                }.execute { older ->
                    val olderChannel = older.bodyAsChannel()
                    withTimeout(5_000) { hub.connected.first { it } }
                    val pending = async { hub.submit(BridgeJob.Translate("one"), 30.seconds) }
                    assertEquals("job" to """{"kind":"translate","id":"job-1","line":"one"}""", readEvent(olderChannel))

                    client
                        .prepareGet("http://127.0.0.1:$port/api/bridge/jobs") {
                            header(HttpHeaders.Authorization, "Bearer $second")
                            header(TEST_REMOTE_HEADER, "127.0.0.1")
                        }.execute { newer ->
                            val newerChannel = newer.bodyAsChannel()
                            assertEquals(BridgeOutcome.Disconnected, pending.await())
                            assertNull(readEvent(olderChannel)) // the older stream ended
                            assertTrue(hub.connected.value)

                            val next = async { hub.submit(BridgeJob.Translate("two"), 30.seconds) }
                            assertEquals(
                                "job" to """{"kind":"translate","id":"job-2","line":"two"}""",
                                readEvent(newerChannel),
                            )
                            next.cancel()
                            assertEquals("cancel" to """{"id":"job-2"}""", readEvent(newerChannel))
                        }
                }
        }
}
