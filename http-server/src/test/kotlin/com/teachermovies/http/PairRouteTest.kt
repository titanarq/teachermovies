package com.teachermovies.http

import com.teachermovies.core.repo.fake.InMemoryTorrentRepository
import com.teachermovies.http.auth.CountingSecureRandom
import com.teachermovies.http.auth.FakeClock
import com.teachermovies.http.auth.InMemorySettingsRepository
import com.teachermovies.http.auth.PairResult
import com.teachermovies.http.auth.PairingManager
import com.teachermovies.http.auth.TokenScope
import com.teachermovies.http.auth.lanClient
import com.teachermovies.http.auth.redactTokenQuery
import com.teachermovies.http.auth.requireBearer
import com.teachermovies.torrent.fake.FakeTorrentEngine
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
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
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairRouteTest {
    private val settings = InMemorySettingsRepository()
    private val clock = FakeClock()
    private val pairing = PairingManager(settings, CountingSecureRandom(), clock)
    private var handlerRuns = 0

    private val engine = FakeTorrentEngine()

    private val deps =
        ServerDeps(
            engine = engine,
            remove = engine::remove,
            space = { null },
            appVersion = "test",
            clock = clock,
            pairing = pairing,
            subtitles = FakeSubtitleStore(),
            library = InMemoryTorrentRepository(),
            allowTestRemoteHeader = true,
        )

    /**
     * The real module plus four protected test routes: phone-only header-only, phone-only
     * header-or-query, bridge-only, and one that takes either scope. The last two stand in for the
     * bridge routes (#275) and the log routes (#269), the only ones a bridge token reaches
     * (ADR-0005 §4). Returns a client that claims a LAN remote address (#59), so these auth-focused
     * tests aren't also exercising the LAN-address guard.
     */
    private fun ApplicationTestBuilder.setUp(): HttpClient {
        application {
            module(deps)
            routing {
                requireBearer(deps.pairing, setOf(TokenScope.PHONE)) {
                    get("/api/test") {
                        handlerRuns++
                        call.respondText("ok")
                    }
                }
                requireBearer(deps.pairing, setOf(TokenScope.PHONE), allowQueryToken = true) {
                    get("/api/test-events") {
                        handlerRuns++
                        call.respondText("ok")
                    }
                }
                requireBearer(deps.pairing, setOf(TokenScope.BRIDGE)) {
                    get("/api/test-bridge") {
                        handlerRuns++
                        call.respondText("bridge")
                    }
                }
                requireBearer(deps.pairing, setOf(TokenScope.PHONE, TokenScope.BRIDGE)) {
                    get("/api/test-logs") {
                        handlerRuns++
                        call.respondText("logs")
                    }
                }
            }
        }
        return lanClient()
    }

    private suspend fun HttpClient.pair(
        pin: String,
        scope: String? = null,
    ): HttpResponse =
        post("/api/pair") {
            contentType(ContentType.Application.Json)
            setBody(pairBody(pin, scope))
        }

    private suspend fun HttpClient.pairedToken(scope: String? = null): String {
        val body = pair(pairing.currentPin(), scope).bodyAsText()
        return Json
            .parseToJsonElement(body)
            .jsonObject["token"]!!
            .jsonPrimitive.content
    }

    private fun pairBody(
        pin: String,
        scope: String?,
    ): String {
        val scopeField = if (scope == null) "" else ",\"scope\":\"$scope\""
        return "{\"pin\":\"$pin\",\"deviceName\":\"Pixel\"$scopeField}"
    }

    private suspend fun HttpResponse.jsonField(name: String): String =
        Json
            .parseToJsonElement(bodyAsText())
            .jsonObject[name]!!
            .jsonPrimitive.content

    private fun wrongPin(): String = ((pairing.currentPin().toInt() + 1) % 1_000_000).toString().padStart(6, '0')

    private suspend fun HttpResponse.assertUnauthorized(sentToken: String? = null) {
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertEquals("Bearer", headers[HttpHeaders.WWWAuthenticate])
        val body = bodyAsText()
        assertEquals("""{"error":"unauthorized","message":"Missing or invalid bearer token"}""", body)
        if (sentToken != null) assertFalse(body.contains(sentToken))
    }

    @Test
    fun `right pin returns a token whose hash is stored`() =
        testApplication {
            val client = setUp()

            val response = client.pair(pairing.currentPin())

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.contentType()!!.match(ContentType.Application.Json))
            val token = response.jsonField("token")
            assertEquals("phone", response.jsonField("scope"))
            assertEquals(setOf(PairingManager.sha256Hex(token)), settings.current.authTokenHashes)
            assertTrue(settings.current.bridgeTokenHashes.isEmpty())
        }

    @Test
    fun `wrong pin is 401 wrong_pin without echoing the pin`() =
        testApplication {
            val client = setUp()
            val pin = wrongPin()

            val response = client.pair(pin)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = response.bodyAsText()
            assertEquals("""{"error":"wrong_pin","message":"Wrong PIN"}""", body)
            assertFalse(body.contains(pin))
        }

    @Test
    fun `sixth attempt within a minute is 429 too_many_attempts`() =
        testApplication {
            val client = setUp()
            repeat(PairingManager.MAX_WRONG_ATTEMPTS) {
                assertEquals(HttpStatusCode.Unauthorized, client.pair(wrongPin()).status)
            }

            val response = client.pair(pairing.currentPin())

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals(
                """{"error":"too_many_attempts","message":"Too many wrong PINs; try again in a minute"}""",
                response.bodyAsText(),
            )
        }

    @Test
    fun `rotated pin is rejected`() =
        testApplication {
            val client = setUp()
            val pin = pairing.currentPin()
            clock.now += PairingManager.PIN_LIFETIME_MS

            assertEquals(HttpStatusCode.Unauthorized, client.pair(pin).status)
        }

    @Test
    fun `malformed pair body is 400 bad_request`() =
        testApplication {
            val client = setUp()

            val response =
                client.post("/api/pair") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"deviceName":"no pin"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                "bad_request",
                Json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject["error"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `pair and status need no token`() =
        testApplication {
            val client = setUp()

            assertEquals(HttpStatusCode.OK, client.get("/api/status").status)
            assertEquals(HttpStatusCode.OK, client.pair(pairing.currentPin()).status)
        }

    @Test
    fun `protected route accepts a paired bearer token`() =
        testApplication {
            val client = setUp()
            val token = client.pairedToken()

            val response = client.get("/api/test") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ok", response.bodyAsText())
        }

    @Test
    fun `protected route rejects a missing token`() =
        testApplication {
            val client = setUp()

            client.get("/api/test").assertUnauthorized()
            assertEquals(0, handlerRuns)
        }

    @Test
    fun `protected route rejects an unknown token without echoing it`() =
        testApplication {
            val client = setUp()
            val bogus = "not-a-real-token-1234567890"

            client.get("/api/test") { bearerAuth(bogus) }.assertUnauthorized(sentToken = bogus)
            client.get("/api/test") { header(HttpHeaders.Authorization, "Basic dXNlcjpwYXNz") }.assertUnauthorized()
            assertEquals(0, handlerRuns)
        }

    @Test
    fun `query token is rejected by default`() =
        testApplication {
            val client = setUp()
            val token = client.pairedToken()

            client.get("/api/test?token=$token").assertUnauthorized(sentToken = token)
            assertEquals(0, handlerRuns)
        }

    @Test
    fun `query token is accepted with allowQueryToken`() =
        testApplication {
            val client = setUp()
            val token = client.pairedToken()

            assertEquals(HttpStatusCode.OK, client.get("/api/test-events?token=$token").status)
            assertEquals(HttpStatusCode.OK, client.get("/api/test-events") { bearerAuth(token) }.status)
            client.get("/api/test-events?token=bogus").assertUnauthorized(sentToken = "bogus")
            client.get("/api/test-events").assertUnauthorized()
        }

    @Test
    fun `token paired before a restart is still accepted`() =
        testApplication {
            val token =
                PairingManager(settings, CountingSecureRandom(), FakeClock()).let { first ->
                    (first.pair(first.currentPin()) as PairResult.Paired).token
                }
            val client = setUp()

            assertEquals(HttpStatusCode.OK, client.get("/api/test") { bearerAuth(token) }.status)
        }

    @Test
    fun `pairing with scope bridge stores its hash apart and echoes the scope`() =
        testApplication {
            val client = setUp()

            val response = client.pair(pairing.currentPin(), scope = "bridge")

            assertEquals(HttpStatusCode.OK, response.status)
            val token = response.jsonField("token")
            assertEquals("bridge", response.jsonField("scope"))
            assertEquals(setOf(PairingManager.sha256Hex(token)), settings.current.bridgeTokenHashes)
            assertTrue(settings.current.authTokenHashes.isEmpty())
            assertEquals("Pixel", settings.current.bridgeDeviceName)
        }

    @Test
    fun `pairing with scope phone is the same as pairing without one`() =
        testApplication {
            val client = setUp()

            assertEquals("phone", client.pair(pairing.currentPin(), scope = "phone").jsonField("scope"))
            assertTrue(settings.current.bridgeTokenHashes.isEmpty())
        }

    @Test
    fun `unknown scope is 400 bad_request and pairs nothing`() =
        testApplication {
            val client = setUp()
            val pin = pairing.currentPin()

            val response = client.pair(pin, scope = "remote")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            val error = Json.parseToJsonElement(body).jsonObject
            assertEquals("bad_request", error["error"]!!.jsonPrimitive.content)
            assertEquals("Unknown scope; expected one of phone, bridge", error["message"]!!.jsonPrimitive.content)
            assertFalse(body.contains(pin))
            assertTrue(settings.current.authTokenHashes.isEmpty())
            assertTrue(settings.current.bridgeTokenHashes.isEmpty())
            // The PIN was not consumed by the refused attempt: the same one still pairs.
            assertEquals(HttpStatusCode.OK, client.pair(pin).status)
        }

    @Test
    fun `a scope is matched exactly, case included`() =
        testApplication {
            val client = setUp()

            assertEquals(HttpStatusCode.BadRequest, client.pair(pairing.currentPin(), scope = "Bridge").status)
        }

    @Test
    fun `a scope that is not a string is 400 bad_request`() =
        testApplication {
            val client = setUp()

            val response =
                client.post("/api/pair") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"pin":"${pairing.currentPin()}","scope":3}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `a bridge token is refused on every phone route`() =
        testApplication {
            val client = setUp()
            val token = client.pairedToken(scope = "bridge")

            client.get("/api/test") { bearerAuth(token) }.assertUnauthorized(sentToken = token)
            client.get("/api/test-events?token=$token").assertUnauthorized(sentToken = token)
            client.get("/api/torrents") { bearerAuth(token) }.assertUnauthorized(sentToken = token)
            client.get("/api/library") { bearerAuth(token) }.assertUnauthorized(sentToken = token)
            client.get("/api/events") { bearerAuth(token) }.assertUnauthorized(sentToken = token)
            assertEquals(0, handlerRuns)
        }

    @Test
    fun `a phone token is refused on a bridge-only route`() =
        testApplication {
            val client = setUp()
            val token = client.pairedToken()

            client.get("/api/test-bridge") { bearerAuth(token) }.assertUnauthorized(sentToken = token)
            assertEquals(0, handlerRuns)
        }

    @Test
    fun `each scope reaches the routes that accept it`() =
        testApplication {
            val client = setUp()
            val phone = client.pairedToken()
            val bridge = client.pairedToken(scope = "bridge")

            val phoneRoute = client.get("/api/test") { bearerAuth(phone) }
            assertEquals(HttpStatusCode.OK, phoneRoute.status)
            assertEquals("ok", phoneRoute.bodyAsText())
            val bridgeRoute = client.get("/api/test-bridge") { bearerAuth(bridge) }
            assertEquals(HttpStatusCode.OK, bridgeRoute.status)
            assertEquals("bridge", bridgeRoute.bodyAsText())
            // A route that takes both scopes, as `/api/logs*` does (ADR-0006 §4).
            assertEquals("logs", client.get("/api/test-logs") { bearerAuth(phone) }.bodyAsText())
            assertEquals("logs", client.get("/api/test-logs") { bearerAuth(bridge) }.bodyAsText())
        }

    @Test
    fun `the pair request never prints the pin`() {
        assertFalse(PairRequest(pin = "482916", scope = "bridge").toString().contains("482916"))
    }

    @Test
    fun `redactTokenQuery hides token query values`() {
        assertEquals("/api/events?token=<redacted>", redactTokenQuery("/api/events?token=abc"))
        assertEquals("/api/x?a=1&token=<redacted>&b=2", redactTokenQuery("/api/x?a=1&token=abc&b=2"))
        assertEquals("/api/x?mytoken=abc", redactTokenQuery("/api/x?mytoken=abc"))
    }
}
