package com.teachermovies.http

import com.teachermovies.http.auth.InMemorySettingsRepository
import com.teachermovies.http.auth.PairResult
import com.teachermovies.http.auth.PairingManager
import com.teachermovies.http.auth.TEST_REMOTE_HEADER
import com.teachermovies.http.auth.lanClient
import com.teachermovies.torrent.api.EngineResult
import com.teachermovies.torrent.fake.FakeTorrentEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * `GET /api/events` (#62): the first `torrents` snapshot arrives immediately, the next one after
 * `FakeTorrentEngine.advance` changes it (throttled to at most one event per second, so this test
 * takes a little over a second), and a request with no token never gets a byte of the stream.
 *
 * The happy path drives a real `embeddedServer` on a loopback port with a real client engine
 * (`ktor-client-cio`) instead of `testApplication`: its in-process test host runs a request's whole
 * pipeline -- including the response body -- to completion before returning anything to the client
 * (`TestApplicationResponse.awaitForResponseCompletion`), which an SSE stream that outlives the
 * request, by design, never does. That real connection still needs `X-Test-Remote` (#59): CIO's
 * `origin.remoteHost` reports the reverse-DNS hostname (`localhost`), not the `127.0.0.1` literal,
 * and `LanAddressPolicy` refuses anything that isn't a literal.
 */
class EventsRouteTest {
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
            subtitles = FakeSubtitleStore(),
            allowTestRemoteHeader = true, // both tests below send `X-Test-Remote` (#59).
        )

    /** Reads one `event: torrents` / `data: ...` frame off [channel], skipping any `: ping` comments. */
    private suspend fun readTorrentsEvent(channel: ByteReadChannel): String =
        withTimeout(5_000) {
            while (true) {
                val eventLine = channel.readUTF8Line() ?: error("stream ended before an event arrived")
                if (eventLine.startsWith(":")) {
                    channel.readUTF8Line() // the blank line after a comment
                    continue
                }
                assertEquals("event: torrents", eventLine)
                val dataLine = channel.readUTF8Line() ?: error("stream ended before a data line arrived")
                assertTrue(dataLine.startsWith("data: "))
                channel.readUTF8Line() // the blank line terminating the frame
                return@withTimeout dataLine.removePrefix("data: ")
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    @Test
    fun `first event is immediate, the next follows a change`() =
        runBlocking {
            val server = embeddedServer(ServerCIO, port = 0) { module(deps) }.start(wait = false)
            val client = HttpClient(ClientCIO)
            try {
                val port = server.engine.resolvedConnectors().first().port
                val token = (pairing.pair(pairing.currentPin()) as PairResult.Paired).token

                client.prepareGet("http://127.0.0.1:$port/api/events?token=$token") {
                    header(TEST_REMOTE_HEADER, "127.0.0.1")
                }.execute { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertTrue(response.contentType()!!.match(ContentType.Text.EventStream))
                    assertEquals("no-cache", response.headers["Cache-Control"])

                    val channel = response.bodyAsChannel()
                    assertEquals("[]", readTorrentsEvent(channel))

                    val id = (engine.addMagnet("magnet:?xt=urn:btih:${"a".repeat(40)}") as EngineResult.Ok).value
                    engine.emitMetadata(id, "Movie", listOf("Movie.mkv" to 100L))
                    engine.advance(id, bytes = 50L, rateBps = 10L, peers = 2)

                    val second = Json.parseToJsonElement(readTorrentsEvent(channel)).jsonArray
                    assertEquals(1, second.size)
                    assertEquals("downloading", second[0].jsonObject["state"]!!.jsonPrimitive.content)
                    assertEquals(50.0, second[0].jsonObject["progress"]!!.jsonPrimitive.content.toDouble(), 0.0)
                }
            } finally {
                client.close()
                server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
            }
        }

    @Test
    fun `without a token, the request is 401 before any event is written`() =
        testApplication {
            application { module(deps) }
            val client = lanClient()

            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/events").status)
        }
}
