package com.teachermovies.bridge.tv

import com.teachermovies.bridge.protocol.BridgeJobResultDto
import com.teachermovies.bridge.protocol.LogsPageDto
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The TV's HTTP API as the bridge sees it (#271, ADR-0005 §1): pairing, the status probe `doctor`
 * needs, and one page of the TV's log ring buffer (ADR-0006 §4). The DTOs of `GET /api/logs` come
 * from `:bridge-protocol`, the module this one and `:http-server` share.
 *
 * [httpClient] only supplies the engine (CIO here); this class derives its own client with a
 * [TIMEOUT_MILLIS] timeout, JSON via kotlinx.serialization and `expectSuccess = false`, so every
 * status code is mapped into an [ApiResult] or a [PairOutcome] instead of thrown.
 *
 * The token travels in the `Authorization: Bearer` header and nowhere else: ADR-0002's `?token=`
 * exception stays exclusive to `GET /api/events`. Nothing here prints a token, a PIN or a header,
 * and an [ApiFailure.Network] reason is a class name or a fixed phrase, so no response body -- which
 * could hold a token -- can leak into a message.
 */
class TvApi(
    httpClient: HttpClient,
) {
    private val client =
        httpClient.config {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = TIMEOUT_MILLIS
                connectTimeoutMillis = TIMEOUT_MILLIS
                socketTimeoutMillis = TIMEOUT_MILLIS
            }
            install(ContentNegotiation) { json(JSON) }
        }

    /**
     * `POST /api/pair` with `scope: "bridge"`: exchanges the PIN the TV shows for a bridge token.
     * [deviceName] is informational; the TV stores nothing about it yet.
     */
    suspend fun pair(
        baseUrl: String,
        pin: String,
        deviceName: String?,
    ): PairOutcome =
        guarded({ PairOutcome.Failed(it) }) {
            val response =
                client.post(url(baseUrl, "/api/pair")) {
                    contentType(ContentType.Application.Json)
                    setBody(PairRequest(pin = pin, deviceName = deviceName, scope = BRIDGE_SCOPE))
                }
            when {
                response.status.isSuccess() -> {
                    val paired = response.body<PairResponse>()
                    if (paired.scope == BRIDGE_SCOPE) {
                        PairOutcome.Paired(token = paired.token, scope = paired.scope)
                    } else {
                        PairOutcome.WrongScope(paired.scope)
                    }
                }

                response.status == HttpStatusCode.TooManyRequests -> {
                    PairOutcome.TooManyAttempts
                }

                else -> {
                    val failure = response.httpFailure()
                    if (failure.status == HttpStatusCode.Unauthorized.value && failure.code == WRONG_PIN_CODE) {
                        PairOutcome.WrongPin
                    } else {
                        PairOutcome.Failed(failure)
                    }
                }
            }
        }

    /** `GET /api/status`, public (ADR-0002): the TV's version, engine state and torrent count. */
    suspend fun status(baseUrl: String): ApiResult<TvStatus> =
        guarded({ ApiResult.Failure(it) }) {
            val response = client.get(url(baseUrl, "/api/status"))
            if (response.status.isSuccess()) {
                ApiResult.Success(response.body<TvStatus>())
            } else {
                ApiResult.Failure(response.httpFailure())
            }
        }

    /**
     * `GET /api/logs` with the bridge token: one page of the TV's ring buffer, oldest first
     * (ADR-0006 §4). A null filter is simply not sent, so the TV applies its own default; `since`
     * is a `seq` cursor and `level` a lower-case minimum.
     */
    suspend fun logs(
        baseUrl: String,
        token: String,
        since: Long?,
        level: String?,
        limit: Int?,
    ): ApiResult<LogsPageDto> =
        guarded({ ApiResult.Failure(it) }) {
            val response =
                client.get(url(baseUrl, "/api/logs")) {
                    bearerAuth(token)
                    parameter("since", since)
                    parameter("level", level)
                    parameter("limit", limit)
                }
            when {
                response.status.isSuccess() -> {
                    ApiResult.Success(response.body<LogsPageDto>())
                }

                response.status == HttpStatusCode.Unauthorized -> {
                    ApiResult.Failure(ApiFailure.Unauthorized)
                }

                else -> {
                    ApiResult.Failure(response.httpFailure())
                }
            }
        }

    /**
     * `GET /api/bridge/jobs` with the bridge token (#275, ADR-0005 §2): holds the job stream open and
     * hands every complete SSE frame to [onEvent], in order, until the stream ends. [onOpen] runs
     * once the TV has accepted the stream (a 2xx), before the first frame. [onEvent] runs on the
     * reading coroutine, so it must return quickly: a job's work belongs in a coroutine of its own,
     * or a `cancel` frame behind it would wait.
     *
     * The stream has no whole-request timeout, but a [STREAM_SOCKET_TIMEOUT_MILLIS] socket timeout:
     * the TV writes a `: ping` every 15 s, so that much silence means the TV vanished without closing
     * the socket. Like every other call it never throws; how the stream ended is the result.
     */
    suspend fun jobStream(
        baseUrl: String,
        token: String,
        onOpen: suspend () -> Unit,
        onEvent: suspend (SseEvent) -> Unit,
    ): JobStreamEnd =
        sseStream(baseUrl, "/api/bridge/jobs", token, since = null, onOpen) { event ->
            onEvent(event)
            true
        }

    /**
     * `GET /api/logs/stream?since=` with the bridge token (#272, ADR-0006 §4-5): the TV's log ring
     * buffer as SSE -- an `event: boot` frame ([com.teachermovies.bridge.protocol.LogStreamBootDto]),
     * the backlog after [since] as one `event: log` frame per entry, then every new line live. A null
     * [since] is not sent (the whole buffer). The token goes in the header only: the TV refuses
     * `?token=` on this route.
     *
     * Frames reach [onEvent] exactly as on [jobStream], with the same socket timeout; [onEvent]
     * returning false stops reading and ends the stream as [JobStreamEnd.Closed], which is how a
     * reader drops a stream it opened with a cursor from another boot.
     */
    suspend fun logStream(
        baseUrl: String,
        token: String,
        since: Long?,
        onOpen: suspend () -> Unit,
        onEvent: suspend (SseEvent) -> Boolean,
    ): JobStreamEnd = sseStream(baseUrl, "/api/logs/stream", token, since, onOpen, onEvent)

    /** The one SSE reader behind [jobStream] and [logStream]; it never throws. */
    private suspend fun sseStream(
        baseUrl: String,
        path: String,
        token: String,
        since: Long?,
        onOpen: suspend () -> Unit,
        onEvent: suspend (SseEvent) -> Boolean,
    ): JobStreamEnd {
        var opened = false
        return try {
            client
                .prepareGet(url(baseUrl, path)) {
                    bearerAuth(token)
                    parameter("since", since)
                    header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
                    timeout {
                        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        socketTimeoutMillis = STREAM_SOCKET_TIMEOUT_MILLIS
                    }
                }.execute { response ->
                    when {
                        response.status.isSuccess() -> {
                            opened = true
                            onOpen()
                            val channel = response.bodyAsChannel()
                            val parser = SseParser()
                            while (true) {
                                val line = channel.readUTF8Line() ?: break
                                val event = parser.feed(line) ?: continue
                                if (!onEvent(event)) break
                            }
                            JobStreamEnd.Closed
                        }

                        response.status == HttpStatusCode.Unauthorized -> {
                            JobStreamEnd.NotOpened(ApiFailure.Unauthorized)
                        }

                        else -> {
                            JobStreamEnd.NotOpened(response.httpFailure())
                        }
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (opened) JobStreamEnd.Broken(reasonFor(e)) else JobStreamEnd.NotOpened(ApiFailure.Network(reasonFor(e)))
        }
    }

    /**
     * `POST /api/bridge/jobs/{id}/result` (#275): the bridge's one answer to job [jobId]. 204 is
     * [ApiResult.Success]; 404 `unknown_job`, 409 `job_closed` and 400 come back as
     * [ApiFailure.Http] with the TV's code.
     */
    suspend fun postJobResult(
        baseUrl: String,
        token: String,
        jobId: String,
        result: BridgeJobResultDto,
    ): ApiResult<Unit> =
        guarded({ ApiResult.Failure(it) }) {
            val response =
                client.post(url(baseUrl, "/api/bridge/jobs/${jobId.encodeURLPathPart()}/result")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody<BridgeJobResultDto>(result)
                }
            when {
                response.status.isSuccess() -> ApiResult.Success(Unit)
                response.status == HttpStatusCode.Unauthorized -> ApiResult.Failure(ApiFailure.Unauthorized)
                else -> ApiResult.Failure(response.httpFailure())
            }
        }

    /**
     * Runs [block], turning any exception except cancellation into [onFailure] with an
     * [ApiFailure.Network]: no [TvApi] method throws.
     */
    private suspend inline fun <R> guarded(
        onFailure: (ApiFailure) -> R,
        block: () -> R,
    ): R =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailure(ApiFailure.Network(reasonFor(e)))
        }

    private suspend fun HttpResponse.httpFailure(): ApiFailure.Http {
        val body = errorBody()
        return ApiFailure.Http(status.value, body?.error, body?.message)
    }

    /** The TV's `{"error","message"[,"id"]}` body, or null when the body is not one. */
    private suspend fun HttpResponse.errorBody(): ErrorBody? =
        try {
            body<ErrorBody>()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    @Serializable
    private data class PairRequest(
        val pin: String,
        val deviceName: String? = null,
        val scope: String? = null,
    ) {
        override fun toString(): String = "PairRequest(pin=<redacted>, deviceName=$deviceName, scope=$scope)"
    }

    /** The TV's success body; [scope] is null only for a TV from before scoped tokens (#270). */
    @Serializable
    private data class PairResponse(
        val token: String,
        val scope: String? = null,
    )

    @Serializable
    private data class ErrorBody(
        val error: String? = null,
        val message: String? = null,
        val id: String? = null,
    )

    companion object {
        /** The scope this bridge pairs with (ADR-0005 §4); a token of any other scope is refused. */
        const val BRIDGE_SCOPE = "bridge"

        /** Per-request timeout: connect, socket and whole request. */
        const val TIMEOUT_MILLIS: Long = 10_000

        /** Longest silence on the job stream before it counts as broken: three missed 15 s pings. */
        const val STREAM_SOCKET_TIMEOUT_MILLIS: Long = 45_000

        private const val WRONG_PIN_CODE = "wrong_pin"

        private val JSON = Json { ignoreUnknownKeys = true }

        private fun url(
            baseUrl: String,
            path: String,
        ): String = baseUrl.trimEnd('/') + path

        /** A short diagnostic that never includes a response body or a request's contents. */
        private fun reasonFor(e: Exception): String =
            when (e) {
                is HttpRequestTimeoutException, is SocketTimeoutException, is ConnectTimeoutException -> {
                    "timeout"
                }

                is SerializationException, is ContentConvertException, is NoTransformationFoundException -> {
                    "unexpected response body"
                }

                else -> {
                    e::class.simpleName ?: "network error"
                }
            }
    }
}
