package com.teachermovies.assistant.translation

import com.anthropic.core.RequestOptions
import com.anthropic.core.http.Headers
import com.anthropic.core.http.HttpClient
import com.anthropic.core.http.HttpRequest
import com.anthropic.core.http.HttpResponse
import com.anthropic.core.http.Interceptor
import com.anthropic.errors.AnthropicIoException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.teachermovies.assistant.speech.SpeechLanguage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture

/**
 * Drives [AnthropicTranslationProvider] through the real SDK client with only the HTTP layer
 * replaced by [FakeHttp] (an SDK [Interceptor] that never calls the network), so request
 * building, status-to-exception mapping and JSON parsing are the SDK's own code.
 */
class AnthropicTranslationProviderTest {
    private val http = FakeHttp()
    private var key: String? = DUMMY_KEY

    private val provider =
        AnthropicTranslationProvider(
            config = { key },
            clientFactory = { apiKey ->
                AnthropicTranslationProvider.buildClient(apiKey, maxRetries = 0, transport = http)
            },
        )

    @Test
    fun `a successful reply is trimmed and returned uncached`() =
        runTest {
            http.respond(200, message("  ¿Dónde está el coche?  "))

            val result = provider.translate("Where is the car?")

            assertEquals(TranslationResult.Translated("¿Dónde está el coche?", fromCache = false), result)
            assertEquals("anthropic", provider.id)
        }

    @Test
    fun `the request uses sonnet 5, no thinking, a low max_tokens, the fixed system prompt and the line alone`() =
        runTest {
            http.respond(200, message("Hola."))

            provider.translate("Hello.")

            val request = http.requests.single()
            assertEquals(listOf(DUMMY_KEY), request.headers.values("x-api-key"))
            assertTrue(request.url().endsWith("/v1/messages"))
            val body = http.bodies.single()
            assertEquals("claude-sonnet-5", body["model"].asText())
            assertEquals(256, body["max_tokens"].asInt())
            assertEquals("disabled", body["thinking"]["type"].asText())
            assertEquals(AnthropicTranslationProvider.SYSTEM_PROMPT, body["system"].asText())
            val messages = body["messages"]
            assertEquals(1, messages.size())
            assertEquals("user", messages[0]["role"].asText())
            assertEquals("Hello.", messages[0]["content"].asText())
        }

    @Test
    fun `a missing key is not configured and opens no connection`() =
        runTest {
            key = null
            assertEquals(TranslationResult.Unavailable("no translation provider configured"), provider.translate("Hi."))
            key = "   "
            assertEquals(TranslationResult.Unavailable("no translation provider configured"), provider.translate("Hi."))
            assertTrue(http.requests.isEmpty())
        }

    @Test
    fun `shortcuts and the unsupported pair never reach the network`() =
        runTest {
            assertEquals(TranslationResult.Translated("", fromCache = true), provider.translate("  "))
            assertEquals(
                TranslationResult.Translated("Hi", fromCache = true),
                provider.translate(" Hi ", SpeechLanguage.EN, SpeechLanguage.EN),
            )
            assertEquals(
                TranslationResult.Unavailable("unsupported language pair"),
                provider.translate("Hola", SpeechLanguage.ES, SpeechLanguage.EN),
            )
            assertTrue(http.requests.isEmpty())
        }

    @Test
    fun `401 and 403 are authentication rejected`() =
        runTest {
            http.respond(401, error("authentication_error"))
            assertEquals(TranslationResult.Unavailable("authentication rejected"), provider.translate("Hi."))
            http.respond(403, error("permission_error"))
            assertEquals(TranslationResult.Unavailable("authentication rejected"), provider.translate("Hi."))
        }

    @Test
    fun `429 is rate limited`() =
        runTest {
            http.respond(429, error("rate_limit_error"))
            assertEquals(TranslationResult.Unavailable("rate limited"), provider.translate("Hi."))
        }

    @Test
    fun `500 and other statuses are api errors with their code`() =
        runTest {
            http.respond(500, error("api_error"))
            assertEquals(TranslationResult.Unavailable("api error 500"), provider.translate("Hi."))
            http.respond(529, error("overloaded_error"))
            assertEquals(TranslationResult.Unavailable("api error 529"), provider.translate("Hi."))
            http.respond(400, error("invalid_request_error"))
            assertEquals(TranslationResult.Unavailable("api error 400"), provider.translate("Hi."))
        }

    @Test
    fun `a body that is not the expected shape is malformed`() =
        runTest {
            http.respond(200, "this is not json")
            assertEquals(TranslationResult.Unavailable("malformed response"), provider.translate("Hi."))
            http.respond(200, """{"unexpected": true}""")
            assertEquals(TranslationResult.Unavailable("malformed response"), provider.translate("Hi."))
        }

    @Test
    fun `a blank reply is an empty translation`() =
        runTest {
            http.respond(200, message("   "))
            assertEquals(TranslationResult.Unavailable("empty translation"), provider.translate("Hi."))
            http.respond(200, messageWithoutText())
            assertEquals(TranslationResult.Unavailable("empty translation"), provider.translate("Hi."))
        }

    @Test
    fun `connection failures are offline`() =
        runTest {
            http.fail(ConnectException("Connection refused"))
            assertEquals(TranslationResult.Offline, provider.translate("Hi."))
            http.fail(UnknownHostException("api.anthropic.com"))
            assertEquals(TranslationResult.Offline, provider.translate("Hi."))
            http.fail(NoRouteToHostException("No route to host"))
            assertEquals(TranslationResult.Offline, provider.translate("Hi."))
        }

    @Test
    fun `a timeout is offline`() =
        runTest {
            http.fail(SocketTimeoutException("timeout"))
            assertEquals(TranslationResult.Offline, provider.translate("Hi."))
        }

    @Test
    fun `the api key never appears in any result`() =
        runTest {
            // Worst case: every failure echoes the key back in its message or body.
            val echo = """{"type":"error","error":{"type":"authentication_error","message":"bad key $DUMMY_KEY"}}"""
            val results = mutableListOf<TranslationResult>()
            for (status in listOf(401, 403, 429, 500, 418)) {
                http.respond(status, echo)
                results += provider.translate("Hi.")
            }
            http.respond(200, "not json $DUMMY_KEY")
            results += provider.translate("Hi.")
            http.fail(ConnectException("refused for $DUMMY_KEY"))
            results += provider.translate("Hi.")
            http.failRaw(IllegalStateException("boom $DUMMY_KEY"))
            results += provider.translate("Hi.")

            assertEquals(8, http.requests.size)
            for (result in results) {
                assertFalse("key leaked in $result", result.toString().contains(DUMMY_KEY))
                assertFalse("key fragment leaked in $result", result.toString().contains("sk-ant-test"))
            }
            assertEquals(TranslationResult.Unavailable("unexpected error IllegalStateException"), results.last())
        }

    @Test
    fun `the sdk client is reused for the same key and rebuilt when the key changes`() =
        runTest {
            var built = 0
            val counting =
                AnthropicTranslationProvider(
                    config = { key },
                    clientFactory = { apiKey ->
                        built++
                        AnthropicTranslationProvider.buildClient(apiKey, maxRetries = 0, transport = http)
                    },
                )
            repeat(2) {
                http.respond(200, message("Hola."))
                counting.translate("Hello.")
            }
            assertEquals(1, built)
            key = "sk-ant-test-other"
            http.respond(200, message("Hola."))
            counting.translate("Hello.")
            assertEquals(2, built)
            assertEquals(listOf("sk-ant-test-other"), http.requests.last().headers.values("x-api-key"))
        }

    private companion object {
        const val DUMMY_KEY = "sk-ant-test-0123456789-dummy"

        fun message(text: String): String =
            """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5",
             "content":[{"type":"text","text":${ObjectMapper().writeValueAsString(text)}}],
             "stop_reason":"end_turn","stop_sequence":null,
             "usage":{"input_tokens":12,"output_tokens":5}}
            """.trimIndent()

        fun messageWithoutText(): String =
            """
            {"id":"msg_2","type":"message","role":"assistant","model":"claude-sonnet-5","content":[],
             "stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":12,"output_tokens":0}}
            """.trimIndent()

        fun error(type: String): String = """{"type":"error","error":{"type":"$type","message":"nope"}}"""
    }
}

/**
 * The fake HTTP layer: replaces the SDK's OkHttp transport. Failures are thrown the way the real
 * OkHttp transport throws them (an [IOException] wrapped in
 * [AnthropicIoException]); [failRaw] throws the given exception as it is.
 */
private class FakeHttp : Interceptor {
    private sealed interface Next {
        data class Respond(
            val status: Int,
            val body: String,
        ) : Next

        data class Fail(
            val error: Throwable,
        ) : Next
    }

    private var next: Next? = null
    val requests = mutableListOf<HttpRequest>()
    val bodies = mutableListOf<JsonNode>()

    fun respond(
        status: Int,
        body: String,
    ) {
        next = Next.Respond(status, body)
    }

    fun fail(cause: IOException) {
        next = Next.Fail(AnthropicIoException("Request failed", cause))
    }

    fun failRaw(error: Throwable) {
        next = Next.Fail(error)
    }

    override fun intercept(httpClient: HttpClient): HttpClient =
        object : HttpClient {
            override fun execute(
                request: HttpRequest,
                requestOptions: RequestOptions,
            ): HttpResponse = answer(request)

            override fun executeAsync(
                request: HttpRequest,
                requestOptions: RequestOptions,
            ): CompletableFuture<HttpResponse> = CompletableFuture.supplyAsync { answer(request) }

            override fun close() = Unit
        }

    private fun answer(request: HttpRequest): HttpResponse {
        requests += request
        request.body?.let { body ->
            val out = ByteArrayOutputStream()
            body.writeTo(out)
            bodies.add(ObjectMapper().readTree(out.toByteArray()))
        }
        return when (val scripted = checkNotNull(next) { "no scripted response" }) {
            is Next.Fail -> throw scripted.error
            is Next.Respond -> FakeResponse(scripted.status, scripted.body)
        }
    }
}

private class FakeResponse(
    private val status: Int,
    private val text: String,
) : HttpResponse {
    override fun statusCode(): Int = status

    override fun headers(): Headers = Headers.builder().put("content-type", "application/json").build()

    override fun body(): InputStream = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    override fun close() = Unit
}
