package com.teachermovies.bridge.tv

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * The bridge's three calls to the TV (#271), against [FakeTv]: what each one sends, and how every
 * answer the TV can give is mapped. Nothing here throws, so a subcommand always has a value to turn
 * into a message and an exit code.
 */
class TvApiTest {
    private val tv = FakeTv()
    private val httpClient = HttpClient(CIO)
    private val api = TvApi(httpClient)

    @After
    fun tearDown() {
        httpClient.close()
        tv.close()
    }

    // --- pair ---

    @Test
    fun `pair asks for the bridge scope and returns the token and the scope`() =
        runBlocking {
            val outcome = api.pair(tv.baseUrl, tv.pin, "portatil-de-prueba")

            assertEquals(PairOutcome.Paired(tv.token, "bridge"), outcome)
            val request = tv.received.last()
            assertEquals("POST", request.method)
            assertEquals("/api/pair", request.path)
            assertNull(request.authorization)
            assertEquals(tv.pin, request.body.jsonField("pin"))
            assertEquals("bridge", request.body.jsonField("scope"))
            assertEquals("portatil-de-prueba", request.body.jsonField("deviceName"))
        }

    @Test
    fun `pair maps a wrong PIN to WrongPin`() =
        runBlocking {
            tv.pin = "otro-pin"

            assertEquals(PairOutcome.WrongPin, api.pair(tv.baseUrl, "482916", "salon"))
        }

    @Test
    fun `pair maps the TV's rate limit to TooManyAttempts`() =
        runBlocking {
            tv.failWith = FakeTv.Answer(HttpStatusCode.TooManyRequests, "too_many_attempts")

            assertEquals(PairOutcome.TooManyAttempts, api.pair(tv.baseUrl, tv.pin, "salon"))
        }

    @Test
    fun `pair refuses a token the TV issued with another scope`() =
        runBlocking {
            tv.issuedScope = "phone"

            assertEquals(PairOutcome.WrongScope("phone"), api.pair(tv.baseUrl, tv.pin, "salon"))
        }

    @Test
    fun `pair refuses a TV from before scoped tokens, which sends no scope at all`() =
        runBlocking {
            tv.issuedScope = null

            assertEquals(PairOutcome.WrongScope(null), api.pair(tv.baseUrl, tv.pin, "salon"))
        }

    @Test
    fun `pair maps a refusal it does not know to an Http failure carrying the TV's own code`() =
        runBlocking {
            tv.failWith = FakeTv.Answer(HttpStatusCode.InternalServerError, "internal")

            val outcome = api.pair(tv.baseUrl, tv.pin, "salon")

            assertEquals(PairOutcome.Failed(ApiFailure.Http(500, "internal", "refused by the fake TV")), outcome)
        }

    @Test
    fun `pair maps an unreachable TV to a Network failure`() =
        runBlocking {
            val outcome = api.pair("http://127.0.0.1:${closedPort()}", tv.pin, "salon")

            assertTrue(outcome is PairOutcome.Failed)
            assertTrue((outcome as PairOutcome.Failed).failure is ApiFailure.Network)
        }

    @Test
    fun `a Paired outcome does not print the token`() {
        val text = PairOutcome.Paired("tok_super-secreto_9f3a", "bridge").toString()

        assertFalse(text.contains("tok_super-secreto_9f3a"))
        assertTrue(text.contains("<redacted>"))
    }

    // --- status ---

    @Test
    fun `status decodes the body and sends no token`() =
        runBlocking {
            val result = api.status(tv.baseUrl)

            assertEquals(ApiResult.Success(TvStatus("1.0", "running", null, null, 3)), result)
            val request = tv.received.last()
            assertEquals("/api/status", request.path)
            assertNull(request.authorization)
        }

    @Test
    fun `status keeps the fields it knows and ignores the ones it does not`() =
        runBlocking {
            tv.statusJson =
                """{"version":"2.1","engine":"stopped","freeBytes":7,"totalBytes":9,"torrents":1,"future":"x"}"""

            val result = api.status(tv.baseUrl)

            assertEquals(ApiResult.Success(TvStatus("2.1", "stopped", 7L, 9L, 1)), result)
        }

    @Test
    fun `status maps a refusal to an Http failure`() =
        runBlocking {
            tv.failWith = FakeTv.Answer(HttpStatusCode.Forbidden, "not_lan")

            val result = api.status(tv.baseUrl)

            assertEquals(ApiResult.Failure(ApiFailure.Http(403, "not_lan", "refused by the fake TV")), result)
        }

    // --- logs ---

    @Test
    fun `logs sends the token in the header and decodes the shared DTOs`() =
        runBlocking {
            val result = api.logs(tv.baseUrl, tv.token, since = null, level = null, limit = null)

            assertEquals(ApiResult.Success(tv.logsPage), result)
            val request = tv.received.last()
            assertEquals("/api/logs", request.path)
            assertEquals("Bearer ${tv.token}", request.authorization)
            assertEquals("", request.query)
            val page = (result as ApiResult.Success).value
            assertEquals("boot-1234abcd", page.bootId)
            assertEquals(2, page.entries.size)
            assertEquals(7L, page.entries.first().seq)
            assertEquals("underrun de 240 ms", page.entries.last().message)
        }

    @Test
    fun `logs sends only the filters it was given, in one query`() =
        runBlocking {
            api.logs(tv.baseUrl, tv.token, since = 7L, level = "warn", limit = 2)

            assertEquals("since=7&level=warn&limit=2", tv.received.last().query)
        }

    @Test
    fun `logs maps a token the TV no longer accepts to Unauthorized`() =
        runBlocking {
            tv.token = "otro-token"

            val result = api.logs(tv.baseUrl, "el-token-olvidado", since = null, level = null, limit = null)

            assertEquals(ApiResult.Failure(ApiFailure.Unauthorized), result)
        }

    @Test
    fun `logs maps a body that is not a page to a Network failure`() =
        runBlocking {
            // A 200 whose body is an error object: the page cannot be decoded from it.
            tv.failWith = FakeTv.Answer(HttpStatusCode.OK, "not_a_page")

            val result = api.logs(tv.baseUrl, tv.token, since = null, level = null, limit = null)

            assertEquals(ApiResult.Failure(ApiFailure.Network("unexpected response body")), result)
        }

    // --- logStream (#272) ---

    @Test
    fun `logStream sends since and the token in the header, and delivers the boot frame and the backlog after since`() =
        runBlocking {
            val events = mutableListOf<SseEvent>()
            var opened = false

            val end =
                api.logStream(tv.baseUrl, tv.token, since = 7L, onOpen = { opened = true }) { event ->
                    events += event
                    event.event != "log"
                }

            assertEquals(JobStreamEnd.Closed, end)
            assertTrue(opened)
            val request = tv.received.last()
            assertEquals("/api/logs/stream", request.path)
            assertEquals("since=7", request.query)
            assertEquals("Bearer ${tv.token}", request.authorization)
            assertEquals(listOf("boot", "log"), events.map { it.event })
            assertEquals("{\"bootId\":\"boot-1234abcd\"}", events[0].data)
            assertTrue(events[1].data.contains("\"seq\":8"))
        }

    @Test
    fun `logStream sends no since when it has none, and maps a refused token to NotOpened Unauthorized`() =
        runBlocking {
            tv.token = "otro-token"

            val end = api.logStream(tv.baseUrl, "el-token-olvidado", since = null, onOpen = {}) { true }

            assertEquals(JobStreamEnd.NotOpened(ApiFailure.Unauthorized), end)
            assertEquals("", tv.received.last().query)
        }

    private fun closedPort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
}
