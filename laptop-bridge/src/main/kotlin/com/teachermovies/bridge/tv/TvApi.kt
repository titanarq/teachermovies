package com.teachermovies.bridge.tv

import com.teachermovies.bridge.protocol.LogsPageDto
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
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

        private const val WRONG_PIN_CODE = "wrong_pin"

        private val JSON = Json { ignoreUnknownKeys = true }

        private fun url(
            baseUrl: String,
            path: String,
        ): String = baseUrl.trimEnd('/') + path

        /** A short diagnostic that never includes a response body or a request's contents. */
        private fun reasonFor(e: Exception): String =
            when (e) {
                is HttpRequestTimeoutException -> {
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
