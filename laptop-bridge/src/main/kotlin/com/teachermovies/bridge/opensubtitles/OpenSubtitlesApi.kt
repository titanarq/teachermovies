package com.teachermovies.bridge.opensubtitles

import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeParseException

/** What one `GET /subtitles` asks for; a null field is not sent. */
data class SearchQuery(
    /** OpenSubtitles language codes, e.g. `en` or `ea,es`. */
    val languages: List<String>,
    val moviehash: String? = null,
    /** With or without the `tt` prefix and leading zeros; sent as OpenSubtitles wants it, digits only. */
    val imdbId: String? = null,
    val title: String? = null,
    val year: Int? = null,
)

/**
 * The OpenSubtitles REST API (`api.opensubtitles.com/api/v1`) as the bridge needs it (#281,
 * ADR-0005 §5): search, download a file, and the account's daily quota.
 *
 * Every request carries the `Api-Key` and a `User-Agent`. `POST /download` and `GET /infos/user`
 * also need a JWT: this class logs in with [credentials] the first time one is needed, keeps the JWT
 * in memory only, and on a 401 logs in once more and repeats the request before giving up. Searches
 * send the JWT when there is one but do not log in for it.
 *
 * [quota] follows every answer that reports the daily download quota. [download] refuses locally,
 * without spending a request, while [Quota.isExhausted] says the reset has not come yet.
 *
 * Nothing here throws or prints: every call returns an [OsResult] or a [DownloadOutcome], and no
 * failure carries a credential, the JWT or a response body. The temporary link a download returns is
 * fetched without the `Api-Key` or the JWT -- it points at another host.
 */
class OpenSubtitlesApi(
    httpClient: HttpClient,
    private val credentials: OpenSubtitlesCredentials,
    private val baseUrl: String = DEFAULT_BASE_URL,
    val quota: QuotaTracker = QuotaTracker(),
    private val now: () -> Instant = Instant::now,
) {
    private val userAgent: String = credentials.userAgent ?: DEFAULT_USER_AGENT

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

    private val loginLock = Mutex()

    @Volatile
    private var jwt: String? = null

    /** `GET /subtitles`: the results that carry a file, in the server's order. */
    suspend fun search(query: SearchQuery): OsResult<List<SubtitleCandidate>> =
        guarded({ OsResult.Failure(it) }) {
            val response =
                withJwtRetry(requireLogin = false) { token ->
                    client.get(url("/subtitles")) {
                        common(token)
                        // Alphabetical and lower-case, as OpenSubtitles asks, or it answers a redirect.
                        parameter("imdb_id", query.imdbId?.let(::imdbDigits))
                        parameter(
                            "languages",
                            query.languages
                                .map { it.lowercase() }
                                .sorted()
                                .joinToString(","),
                        )
                        parameter("moviehash", query.moviehash?.lowercase())
                        parameter("query", query.title?.lowercase())
                        parameter("year", query.year)
                    }
                }
            when (response) {
                is OsResult.Failure -> {
                    response
                }

                is OsResult.Success -> {
                    val page = response.value.body<SearchPage>()
                    OsResult.Success(page.data.mapNotNull { it.toCandidate() })
                }
            }
        }

    /** `POST /download`: a temporary link to [fileId] as SRT, spending one download of the quota. */
    suspend fun download(fileId: Long): DownloadOutcome {
        if (quota.current.isExhausted(now())) return DownloadOutcome.QuotaExhausted(quota.current)
        return guarded({ DownloadOutcome.Failed(it) }) {
            val result =
                withJwtRetry(requireLogin = true) { token ->
                    client.post(url("/download")) {
                        common(token)
                        contentType(ContentType.Application.Json)
                        setBody(DownloadRequest(fileId = fileId))
                    }
                }
            when (result) {
                is OsResult.Failure -> {
                    DownloadOutcome.Failed(result.failure)
                }

                is OsResult.Success -> {
                    val response = result.value
                    if (response.status == HttpStatusCode.NotAcceptable) {
                        // 406: the day's downloads are spent. Remember it even if the body said nothing.
                        val answer = response.bodyOrNull<DownloadAnswer>()
                        quota.update(remaining = answer?.remaining ?: 0, resetsAt = parseInstant(answer?.resetTimeUtc))
                        DownloadOutcome.QuotaExhausted(quota.current)
                    } else {
                        val answer = response.body<DownloadAnswer>()
                        quota.update(remaining = answer.remaining, resetsAt = parseInstant(answer.resetTimeUtc))
                        val link = answer.link
                        if (link == null) {
                            DownloadOutcome.Failed(OsFailure.Network(UNEXPECTED_BODY))
                        } else {
                            DownloadOutcome.Link(link = link, fileName = answer.fileName)
                        }
                    }
                }
            }
        }
    }

    /** The file behind a [DownloadOutcome.Link], raw; no credential travels with this request. */
    suspend fun fetch(link: String): OsResult<ByteArray> =
        guarded({ OsResult.Failure(it) }) {
            val response = client.get(link) { header(HttpHeaders.UserAgent, userAgent) }
            if (response.status.isSuccess()) {
                OsResult.Success(response.readRawBytes())
            } else {
                OsResult.Failure(failureFor(response.status))
            }
        }

    /** `GET /infos/user`: asks OpenSubtitles for the quota now, and updates [quota] with it. */
    suspend fun refreshQuota(): OsResult<Quota> =
        guarded({ OsResult.Failure(it) }) {
            when (
                val result =
                    withJwtRetry(
                        requireLogin = true,
                    ) { token -> client.get(url("/infos/user")) { common(token) } }
            ) {
                is OsResult.Failure -> {
                    result
                }

                is OsResult.Success -> {
                    val user = result.value.body<UserInfoAnswer>().data
                    quota.update(remaining = user.remainingDownloads, allowed = user.allowedDownloads)
                    OsResult.Success(quota.current)
                }
            }
        }

    /**
     * Runs [request] with the current JWT (logging in first when [requireLogin] and there is none).
     * A 401 drops the JWT, logs in again and repeats [request] once. Answers 2xx and, for
     * `/download`, 406 come back as [OsResult.Success]; everything else as a failure.
     */
    private suspend fun withJwtRetry(
        requireLogin: Boolean,
        request: suspend (String?) -> HttpResponse,
    ): OsResult<HttpResponse> {
        var token = jwt
        if (token == null && requireLogin) {
            when (val login = login(stale = null)) {
                is OsResult.Failure -> return login
                is OsResult.Success -> token = login.value
            }
        }
        var response = request(token)
        if (response.status == HttpStatusCode.Unauthorized) {
            token =
                when (val login = login(stale = token)) {
                    is OsResult.Failure -> return login
                    is OsResult.Success -> login.value
                }
            response = request(token)
        }
        return when {
            response.status.isSuccess() || response.status == HttpStatusCode.NotAcceptable -> OsResult.Success(response)
            else -> OsResult.Failure(failureFor(response.status))
        }
    }

    /**
     * `POST /login`, unless another caller already replaced [stale] with a fresh JWT while this one
     * waited for the lock -- then that one is used, so a burst of 401s logs in once.
     */
    private suspend fun login(stale: String?): OsResult<String> =
        loginLock.withLock {
            val current = jwt
            if (current != null && current != stale) return@withLock OsResult.Success(current)
            jwt = null
            val response =
                client.post(url("/login")) {
                    common(token = null)
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest(username = credentials.username, password = credentials.password))
                }
            when {
                response.status.isSuccess() -> {
                    val answer = response.body<LoginAnswer>()
                    quota.update(allowed = answer.user?.allowedDownloads)
                    jwt = answer.token
                    OsResult.Success(answer.token)
                }

                response.status == HttpStatusCode.Unauthorized -> {
                    OsResult.Failure(OsFailure.LoginRefused)
                }

                else -> {
                    OsResult.Failure(failureFor(response.status))
                }
            }
        }

    /** The body as [T], or null when it is not one. */
    private suspend inline fun <reified T> HttpResponse.bodyOrNull(): T? =
        try {
            body<T>()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private fun HttpRequestBuilder.common(token: String?) {
        header(API_KEY_HEADER, credentials.apiKey)
        header(HttpHeaders.UserAgent, userAgent)
        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        if (token != null) bearerAuth(token)
    }

    private fun url(path: String): String = baseUrl.trimEnd('/') + path

    /** Runs [block], turning any exception except cancellation into [onFailure]. */
    private suspend inline fun <R> guarded(
        onFailure: (OsFailure) -> R,
        block: () -> R,
    ): R =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailure(OsFailure.Network(reasonFor(e)))
        }

    @Serializable
    private data class LoginRequest(
        val username: String,
        val password: String,
    ) {
        override fun toString(): String = "LoginRequest(username=<redacted>, password=<redacted>)"
    }

    @Serializable
    private data class LoginAnswer(
        val token: String,
        val user: UserInfo? = null,
    ) {
        override fun toString(): String = "LoginAnswer(token=<redacted>, user=$user)"
    }

    @Serializable
    private data class UserInfoAnswer(
        val data: UserInfo,
    )

    @Serializable
    private data class UserInfo(
        @SerialName("allowed_downloads") val allowedDownloads: Int? = null,
        @SerialName("remaining_downloads") val remainingDownloads: Int? = null,
    )

    @Serializable
    private data class DownloadRequest(
        @SerialName("file_id") val fileId: Long,
        @SerialName("sub_format") val subFormat: String = SUB_FORMAT,
    )

    @Serializable
    private data class DownloadAnswer(
        val link: String? = null,
        @SerialName("file_name") val fileName: String? = null,
        val remaining: Int? = null,
        @SerialName("reset_time_utc") val resetTimeUtc: String? = null,
    )

    @Serializable
    private data class SearchPage(
        val data: List<SearchItem> = emptyList(),
    )

    @Serializable
    private data class SearchItem(
        val attributes: SearchAttributes,
    ) {
        fun toCandidate(): SubtitleCandidate? {
            val file = attributes.files.firstOrNull() ?: return null
            val language = attributes.language ?: return null
            return SubtitleCandidate(
                fileId = file.fileId,
                fileName = file.fileName,
                languageCode = language.lowercase(),
                hearingImpaired = attributes.hearingImpaired,
                machineTranslated = attributes.machineTranslated,
                aiTranslated = attributes.aiTranslated,
                foreignPartsOnly = attributes.foreignPartsOnly,
                hashMatch = attributes.moviehashMatch,
                fromTrusted = attributes.fromTrusted,
                downloadCount = attributes.downloadCount,
                release = attributes.release,
            )
        }
    }

    @Serializable
    private data class SearchAttributes(
        val language: String? = null,
        @SerialName("hearing_impaired") val hearingImpaired: Boolean = false,
        @SerialName("machine_translated") val machineTranslated: Boolean = false,
        @SerialName("ai_translated") val aiTranslated: Boolean = false,
        @SerialName("foreign_parts_only") val foreignPartsOnly: Boolean = false,
        @SerialName("moviehash_match") val moviehashMatch: Boolean = false,
        @SerialName("from_trusted") val fromTrusted: Boolean = false,
        @SerialName("download_count") val downloadCount: Int = 0,
        val release: String? = null,
        val files: List<SearchFile> = emptyList(),
    )

    @Serializable
    private data class SearchFile(
        @SerialName("file_id") val fileId: Long,
        @SerialName("file_name") val fileName: String? = null,
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://api.opensubtitles.com/api/v1"

        /** Sent when the credentials file names no `User-Agent` of its own. */
        const val DEFAULT_USER_AGENT = "teachermovies-bridge v1.0"

        const val API_KEY_HEADER = "Api-Key"

        /** Per-request timeout: connect, socket and whole request. */
        const val TIMEOUT_MILLIS: Long = 20_000

        private const val SUB_FORMAT = "srt"

        private const val UNEXPECTED_BODY = "unexpected response body"

        /**
         * `coerceInputValues` so a `null` where a flag or a count is expected reads as its default;
         * `encodeDefaults` so `sub_format` is actually sent.
         */
        private val JSON =
            Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                encodeDefaults = true
            }

        /** `tt0133093` -> `133093`, the form OpenSubtitles documents for `imdb_id`. */
        internal fun imdbDigits(imdbId: String): String? =
            imdbId
                .trim()
                .removePrefix("tt")
                .trimStart('0')
                .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

        private fun parseInstant(text: String?): Instant? =
            try {
                text?.let(Instant::parse)
            } catch (_: DateTimeParseException) {
                null
            }

        private fun failureFor(status: HttpStatusCode): OsFailure =
            when (status) {
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> OsFailure.Unauthorized
                HttpStatusCode.TooManyRequests -> OsFailure.RateLimited
                else -> OsFailure.Http(status.value)
            }

        /** A short diagnostic that never includes a response body or a request's contents. */
        private fun reasonFor(e: Exception): String =
            when (e) {
                is HttpRequestTimeoutException -> {
                    "timeout"
                }

                is SerializationException, is ContentConvertException, is NoTransformationFoundException -> {
                    UNEXPECTED_BODY
                }

                else -> {
                    e::class.simpleName ?: "network error"
                }
            }
    }
}
