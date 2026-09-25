package com.teachermovies.mobile.api

import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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
 * [TvApi] over Ktor (#196, ADR-0004 "Ktor client in `:mobile-app`"). The given [httpClient]
 * supplies the engine (CIO in the app); this class derives its own client from it with a 10 s
 * [HttpTimeout], JSON via kotlinx.serialization (`ignoreUnknownKeys`) and `expectSuccess = false`,
 * so every status code is mapped here rather than thrown.
 *
 * The token travels only in the `Authorization: Bearer` header, never in the URL. Nothing here logs
 * a token, a PIN or a header, and a [ApiFailure.Network] reason is only an exception class name or a
 * fixed phrase, so no response body (which could hold a token) leaks into it.
 */
class KtorTvApi(
    httpClient: HttpClient,
) : TvApi {
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

    override suspend fun status(baseUrl: String): ApiResult<TvStatus> =
        guarded({ ApiResult.Failure(it) }) {
            val response = client.get(url(baseUrl, "/api/status"))
            if (response.status.isSuccess()) {
                ApiResult.Success(response.body<TvStatus>())
            } else {
                ApiResult.Failure(response.httpFailure())
            }
        }

    override suspend fun pair(
        baseUrl: String,
        pin: String,
        deviceName: String,
    ): PairOutcome =
        guarded({ PairOutcome.Failed(it) }) {
            val response =
                client.post(url(baseUrl, "/api/pair")) {
                    contentType(ContentType.Application.Json)
                    setBody(PairRequest(pin = pin, deviceName = deviceName))
                }
            when {
                response.status.isSuccess() -> {
                    PairOutcome.Paired(response.body<PairResponse>().token)
                }

                response.status == HttpStatusCode.TooManyRequests -> {
                    PairOutcome.TooManyAttempts
                }

                else -> {
                    val failure = response.httpFailure()
                    if (failure.status == HttpStatusCode.Unauthorized.value && failure.code == "wrong_pin") {
                        PairOutcome.WrongPin
                    } else {
                        PairOutcome.Failed(failure)
                    }
                }
            }
        }

    override suspend fun torrents(
        baseUrl: String,
        token: String,
    ): ApiResult<List<TorrentSummary>> =
        guarded({ ApiResult.Failure(it) }) {
            val response = client.get(url(baseUrl, "/api/torrents")) { bearerAuth(token) }
            when {
                response.status.isSuccess() -> ApiResult.Success(response.body<List<TorrentSummary>>())
                response.status == HttpStatusCode.Unauthorized -> ApiResult.Failure(ApiFailure.Unauthorized)
                else -> ApiResult.Failure(response.httpFailure())
            }
        }

    override suspend fun addMagnet(
        baseUrl: String,
        token: String,
        magnet: String,
    ): AddMagnetOutcome =
        guarded({ AddMagnetOutcome.Failed(it) }) {
            val response =
                client.post(url(baseUrl, "/api/torrents/magnet")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(AddMagnetRequest(magnet = magnet))
                }
            if (response.status.isSuccess()) {
                val added = response.body<AddMagnetResponse>()
                return@guarded AddMagnetOutcome.Added(id = added.id, state = added.state)
            }
            if (response.status == HttpStatusCode.Unauthorized) {
                return@guarded AddMagnetOutcome.Failed(ApiFailure.Unauthorized)
            }
            val body = response.errorBody()
            val failure = ApiFailure.Http(response.status.value, body?.error, body?.message)
            when {
                response.status == HttpStatusCode.Conflict && body?.error == "already_exists" && body.id != null -> {
                    AddMagnetOutcome.AlreadyExists(body.id)
                }

                response.status == HttpStatusCode.BadRequest && body?.error == "invalid_magnet" -> {
                    AddMagnetOutcome.InvalidMagnet
                }

                else -> {
                    AddMagnetOutcome.Failed(failure)
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

    /** The server's `{"error","message"[,"id"]}` body, or `null` when the body is not one. */
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
        val deviceName: String,
    )

    @Serializable
    private data class PairResponse(
        val token: String,
    )

    @Serializable
    private data class AddMagnetRequest(
        val magnet: String,
    )

    @Serializable
    private data class AddMagnetResponse(
        val id: String,
        val state: String,
    )

    @Serializable
    private data class ErrorBody(
        val error: String? = null,
        val message: String? = null,
        val id: String? = null,
    )

    companion object {
        /** Per-request timeout (connect, socket and whole request). */
        const val TIMEOUT_MILLIS: Long = 10_000

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
