package com.teachermovies.http

import com.teachermovies.bridge.protocol.BridgeJobProtocol
import com.teachermovies.core.repo.fake.InMemoryTorrentRepository
import com.teachermovies.http.auth.InMemorySettingsRepository
import com.teachermovies.http.auth.PairResult
import com.teachermovies.http.auth.PairingManager
import com.teachermovies.http.auth.TokenScope
import com.teachermovies.http.auth.lanClient
import com.teachermovies.http.bridge.BridgeJob
import com.teachermovies.http.bridge.BridgeJobHub
import com.teachermovies.http.bridge.BridgeOutcome
import com.teachermovies.http.bridge.BridgeStreamEvent
import com.teachermovies.torrent.fake.FakeTorrentEngine
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import kotlin.time.Duration.Companion.seconds

/**
 * `POST /api/bridge/jobs/{id}/result` (#275) and the auth of both bridge routes, in-process: the
 * stream side is opened straight on the hub, so no SSE response has to outlive a request here (the
 * real stream is [BridgeJobsStreamTest]).
 */
class BridgeRoutesTest {
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

    private suspend fun token(scope: TokenScope): String =
        (pairing.pair(pairing.currentPin(), scope) as PairResult.Paired).token

    private suspend fun HttpClient.postResult(
        id: String,
        token: String,
        body: String,
    ): HttpResponse =
        post("/api/bridge/jobs/$id/result") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `a posted result is 204 and resolves the waiting job, and a second one is 409`() =
        testApplication {
            application { module(deps) }
            val client = lanClient()
            val bridgeToken = token(TokenScope.BRIDGE)
            val stream = hub.connect()
            coroutineScope {
                val outcome = async { hub.submit(BridgeJob.Translate("Break a leg!"), 30.seconds) }
                assertTrue(stream.events.receive() is BridgeStreamEvent.Job)

                val ok = client.postResult("job-1", bridgeToken, """{"status":"ok","text":"¡Mucha suerte!"}""")
                assertEquals(HttpStatusCode.NoContent, ok.status)
                assertEquals(BridgeOutcome.Done("¡Mucha suerte!"), outcome.await())

                val again = client.postResult("job-1", bridgeToken, """{"status":"ok","text":"otra"}""")
                assertEquals(HttpStatusCode.Conflict, again.status)
                assertTrue(again.bodyAsText().contains("\"job_closed\""))
            }
        }

    @Test
    fun `an error result resolves BridgeError`() =
        testApplication {
            application { module(deps) }
            val client = lanClient()
            val bridgeToken = token(TokenScope.BRIDGE)
            val stream = hub.connect()
            coroutineScope {
                val outcome = async { hub.submit(BridgeJob.Translate("hi"), 30.seconds) }
                stream.events.receive()
                val response = client.postResult("job-1", bridgeToken, """{"status":"error","code":"daily_cap"}""")
                assertEquals(HttpStatusCode.NoContent, response.status)
                assertEquals(BridgeOutcome.BridgeError("daily_cap", null), outcome.await())
            }
        }

    @Test
    fun `a result for a job never issued is 404`() =
        testApplication {
            application { module(deps) }
            val response = lanClient().postResult("nope", token(TokenScope.BRIDGE), """{"status":"ok","text":"x"}""")
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("\"unknown_job\""))
        }

    @Test
    fun `a malformed or oversized result is 400 and leaves the job waiting`() =
        testApplication {
            application { module(deps) }
            val client = lanClient()
            val bridgeToken = token(TokenScope.BRIDGE)
            val stream = hub.connect()
            coroutineScope {
                val outcome = async { hub.submit(BridgeJob.Translate("hi"), 30.seconds) }
                stream.events.receive()
                val bad =
                    listOf(
                        "not json",
                        "{}",
                        """{"status":"maybe","text":"x"}""",
                        """{"status":"ok"}""",
                        """{"status":"error"}""",
                    )
                for (body in bad) {
                    assertEquals(body, HttpStatusCode.BadRequest, client.postResult("job-1", bridgeToken, body).status)
                }
                val huge = """{"status":"ok","text":"${"a".repeat(BridgeJobProtocol.MAX_PAYLOAD_BYTES)}"}"""
                val tooLarge = client.postResult("job-1", bridgeToken, huge)
                assertEquals(HttpStatusCode.BadRequest, tooLarge.status)
                assertTrue(tooLarge.bodyAsText().contains("\"too_large\""))

                assertEquals(
                    HttpStatusCode.NoContent,
                    client.postResult("job-1", bridgeToken, """{"status":"ok","text":"hola"}""").status,
                )
                assertEquals(BridgeOutcome.Done("hola"), outcome.await())
            }
        }

    @Test
    fun `phone tokens, query tokens and no token are 401 on both bridge routes`() =
        testApplication {
            application { module(deps) }
            val client = lanClient()
            val phoneToken = token(TokenScope.PHONE)
            val bridgeToken = token(TokenScope.BRIDGE)

            val phoneStream = client.get("/api/bridge/jobs") { header(HttpHeaders.Authorization, "Bearer $phoneToken") }
            assertEquals(HttpStatusCode.Unauthorized, phoneStream.status)
            assertEquals("Bearer", phoneStream.headers[HttpHeaders.WWWAuthenticate])
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/bridge/jobs").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/bridge/jobs?token=$bridgeToken").status)

            assertEquals(
                HttpStatusCode.Unauthorized,
                client.postResult("job-1", phoneToken, """{"status":"ok","text":"x"}""").status,
            )
            val noToken = client.post("/api/bridge/jobs/job-1/result?token=$bridgeToken") { setBody("{}") }
            assertEquals(HttpStatusCode.Unauthorized, noToken.status)
            assertEquals(false, hub.connected.value)
        }
}
