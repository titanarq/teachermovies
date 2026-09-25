package com.teachermovies.mobile.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/** `KtorTvApi` (#196) against a real loopback CIO server answering with fixed JSON. */
class KtorTvApiTest {
    private val server = LoopbackTvServer()
    private val httpClient = HttpClient(CIO)
    private val api = KtorTvApi(httpClient)
    private val token = "tok-123"
    private val magnet = "magnet:?xt=urn:btih:${"a".repeat(40)}"

    @After
    fun tearDown() {
        httpClient.close()
        server.close()
    }

    private fun lastRequest() = server.received.last()

    private fun jsonField(
        body: String,
        name: String,
    ) = Json
        .parseToJsonElement(body)
        .jsonObject[name]!!
        .jsonPrimitive.content

    // --- status ---

    @Test
    fun `status decodes the body, ignores unknown fields and sends no token`() =
        runBlocking {
            server.answer(
                HttpStatusCode.OK,
                """{"version":"1.0","engine":"running","freeBytes":123,"totalBytes":456,"torrents":2,"newField":true}""",
            )

            val result = api.status(server.baseUrl)

            assertEquals(ApiResult.Success(TvStatus("1.0", "running", 123L, 456L, 2)), result)
            assertEquals("GET", lastRequest().method)
            assertEquals("/api/status", lastRequest().path)
            assertNull(lastRequest().authorization)
        }

    @Test
    fun `status accepts null space and a trailing slash in the base url`() =
        runBlocking {
            server.answer(
                HttpStatusCode.OK,
                """{"version":"1.0","engine":"stopped","freeBytes":null,"totalBytes":null,"torrents":0}""",
            )

            val result = api.status(server.baseUrl + "/")

            assertEquals(ApiResult.Success(TvStatus("1.0", "stopped", null, null, 0)), result)
            assertEquals("/api/status", lastRequest().path)
        }

    @Test
    fun `a 500 with an error body is Http with its code and message`() =
        runBlocking {
            server.answer(HttpStatusCode.InternalServerError, """{"error":"io_error","message":"Disk failed"}""")

            assertEquals(ApiResult.Failure(ApiFailure.Http(500, "io_error", "Disk failed")), api.status(server.baseUrl))
        }

    @Test
    fun `a non-2xx without an error body is Http with null code and message`() =
        runBlocking {
            server.answer(HttpStatusCode.BadGateway, "<html>nope</html>")

            assertEquals(ApiResult.Failure(ApiFailure.Http(502, null, null)), api.status(server.baseUrl))
        }

    @Test
    fun `a malformed body is Network`() =
        runBlocking {
            server.answer(HttpStatusCode.OK, """{"version":"1.0",""")

            val result = api.status(server.baseUrl)

            assertTrue(result.toString(), (result as ApiResult.Failure).failure is ApiFailure.Network)
        }

    @Test
    fun `a body of the wrong shape is Network`() =
        runBlocking {
            server.answer(HttpStatusCode.OK, """{"unexpected":1}""")

            val result = api.status(server.baseUrl)

            assertTrue(result.toString(), (result as ApiResult.Failure).failure is ApiFailure.Network)
        }

    @Test
    fun `a closed port is Network on every call`() =
        runBlocking {
            val port = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
            val closed = "http://127.0.0.1:$port"

            val status = api.status(closed)
            val pair = api.pair(closed, "123456", "Phone")
            val torrents = api.torrents(closed, token)
            val add = api.addMagnet(closed, token, magnet)

            assertTrue(status.toString(), (status as ApiResult.Failure).failure is ApiFailure.Network)
            assertTrue(pair.toString(), (pair as PairOutcome.Failed).failure is ApiFailure.Network)
            assertTrue(torrents.toString(), (torrents as ApiResult.Failure).failure is ApiFailure.Network)
            assertTrue(add.toString(), (add as AddMagnetOutcome.Failed).failure is ApiFailure.Network)
        }

    // --- pair ---

    @Test
    fun `pair posts pin and device name without a token and returns the token`() =
        runBlocking {
            server.answer(HttpStatusCode.OK, """{"token":"new-token"}""")

            val outcome = api.pair(server.baseUrl, "482916", "Pixel 8")

            assertEquals(PairOutcome.Paired("new-token"), outcome)
            val request = lastRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/pair", request.path)
            assertNull(request.authorization)
            assertEquals("482916", jsonField(request.body, "pin"))
            assertEquals("Pixel 8", jsonField(request.body, "deviceName"))
        }

    @Test
    fun `pair maps 401 wrong_pin to WrongPin`() =
        runBlocking {
            server.answer(HttpStatusCode.Unauthorized, """{"error":"wrong_pin","message":"Wrong PIN"}""")

            assertEquals(PairOutcome.WrongPin, api.pair(server.baseUrl, "000000", "Phone"))
        }

    @Test
    fun `pair maps 429 to TooManyAttempts`() =
        runBlocking {
            server.answer(HttpStatusCode.TooManyRequests, """{"error":"too_many_attempts","message":"Wait"}""")

            assertEquals(PairOutcome.TooManyAttempts, api.pair(server.baseUrl, "000000", "Phone"))
        }

    @Test
    fun `pair maps any other error to Failed Http`() =
        runBlocking {
            server.answer(HttpStatusCode.BadRequest, """{"error":"bad_request","message":"Bad body"}""")

            assertEquals(
                PairOutcome.Failed(ApiFailure.Http(400, "bad_request", "Bad body")),
                api.pair(server.baseUrl, "1", "Phone"),
            )
        }

    // --- torrents ---

    @Test
    fun `torrents sends the bearer header, not a query token, and decodes the list`() =
        runBlocking {
            server.answer(
                HttpStatusCode.OK,
                """[{"id":"abc","name":"Movie","state":"downloading","progress":42.5,"downloadedBytes":50,
                   |"totalBytes":100,"downloadSpeed":10,"uploadSpeed":3,"peers":2,"etaSeconds":5,"ratio":0.1},
                   |{"id":"def","name":"","state":"fetching_metadata","progress":0.0,"downloadedBytes":0,
                   |"totalBytes":0,"downloadSpeed":0,"uploadSpeed":0,"peers":0,"etaSeconds":null,"ratio":0.0}]
                """.trimMargin(),
            )

            val result = api.torrents(server.baseUrl, token)

            assertEquals(
                ApiResult.Success(
                    listOf(
                        TorrentSummary("abc", "Movie", "downloading", 42.5, 50, 100, 10, 2, 5),
                        TorrentSummary("def", "", "fetching_metadata", 0.0, 0, 0, 0, 0, null),
                    ),
                ),
                result,
            )
            val request = lastRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/torrents", request.path)
            assertEquals("Bearer $token", request.authorization)
            assertEquals("", request.query)
        }

    @Test
    fun `torrents maps 401 to Unauthorized`() =
        runBlocking {
            server.answer(HttpStatusCode.Unauthorized, """{"error":"unauthorized","message":"Pair first"}""")

            assertEquals(ApiResult.Failure(ApiFailure.Unauthorized), api.torrents(server.baseUrl, token))
        }

    // --- addMagnet ---

    @Test
    fun `addMagnet posts the magnet with the bearer header and returns Added on 201`() =
        runBlocking {
            server.answer(HttpStatusCode.Created, """{"id":"abc","state":"fetching_metadata"}""")

            val outcome = api.addMagnet(server.baseUrl, token, magnet)

            assertEquals(AddMagnetOutcome.Added("abc", "fetching_metadata"), outcome)
            val request = lastRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/torrents/magnet", request.path)
            assertEquals("Bearer $token", request.authorization)
            assertEquals("", request.query)
            assertEquals(magnet, jsonField(request.body, "magnet"))
        }

    @Test
    fun `addMagnet maps 409 already_exists to AlreadyExists with its id`() =
        runBlocking {
            server.answer(
                HttpStatusCode.Conflict,
                """{"error":"already_exists","message":"Already there","id":"abc"}""",
            )

            assertEquals(AddMagnetOutcome.AlreadyExists("abc"), api.addMagnet(server.baseUrl, token, magnet))
        }

    @Test
    fun `addMagnet maps 400 invalid_magnet to InvalidMagnet`() =
        runBlocking {
            server.answer(HttpStatusCode.BadRequest, """{"error":"invalid_magnet","message":"Not a magnet"}""")

            assertEquals(AddMagnetOutcome.InvalidMagnet, api.addMagnet(server.baseUrl, token, "magnet:?nope"))
        }

    @Test
    fun `addMagnet maps 401 to Failed Unauthorized`() =
        runBlocking {
            server.answer(HttpStatusCode.Unauthorized, """{"error":"unauthorized","message":"Pair first"}""")

            assertEquals(AddMagnetOutcome.Failed(ApiFailure.Unauthorized), api.addMagnet(server.baseUrl, token, magnet))
        }

    @Test
    fun `addMagnet maps a 500 with an error body to Failed Http`() =
        runBlocking {
            server.answer(HttpStatusCode.InternalServerError, """{"error":"io_error","message":"Disk failed"}""")

            assertEquals(
                AddMagnetOutcome.Failed(ApiFailure.Http(500, "io_error", "Disk failed")),
                api.addMagnet(server.baseUrl, token, magnet),
            )
        }

    @Test
    fun `the redacted token never shows in a Paired toString`() {
        assertTrue("secret" !in PairOutcome.Paired("secret").toString())
    }
}
