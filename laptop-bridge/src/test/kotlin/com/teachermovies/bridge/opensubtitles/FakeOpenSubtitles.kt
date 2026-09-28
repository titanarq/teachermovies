package com.teachermovies.bridge.opensubtitles

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A real Ktor CIO server on a loopback port standing in for OpenSubtitles (#281): `POST /login`,
 * `GET /subtitles`, `POST /download`, `GET /infos/user` under `/api/v1`, and the temporary download
 * links under `/files/{id}`, shaped as the OpenSubtitles REST API documents them.
 *
 * Every request is recorded with the headers that matter ([Received]), so a test can assert what the
 * bridge sent and -- as important -- what it did not. Knobs: the accepted [apiKey], [username] and
 * [password]; the [subtitles] each search key returns; the [files] behind each file id; the daily
 * quota ([remaining], [allowed], [resetTimeUtc]); [expireJwt] to make the next protected call 401;
 * and [failSearchWith] to make searches answer one status.
 */
class FakeOpenSubtitles : Closeable {
    data class Received(
        val method: String,
        val path: String,
        val query: String,
        val apiKey: String?,
        val userAgent: String?,
        val authorization: String?,
        val body: String,
    )

    /** One search result; [hashMatch] is only reported when the search was by moviehash. */
    data class Sub(
        val fileId: Long,
        val language: String,
        val hearingImpaired: Boolean = false,
        val machineTranslated: Boolean = false,
        val aiTranslated: Boolean = false,
        val foreignPartsOnly: Boolean = false,
        val hashMatch: Boolean = true,
        val fromTrusted: Boolean = false,
        val downloadCount: Int = 0,
    )

    @Volatile var apiKey: String = "api-key-fake-1234"

    @Volatile var username: String = "usuario"

    @Volatile var password: String = "contraseña-secreta"

    /** Results per search key: `hash:<moviehash>`, `imdb:<digits>` or `query:<title>`. */
    val subtitles: MutableMap<String, List<Sub>> = mutableMapOf()

    /** The raw bytes behind each file id. */
    val files: MutableMap<Long, ByteArray> = mutableMapOf()

    @Volatile var remaining: Int = 20

    @Volatile var allowed: Int = 20

    @Volatile var resetTimeUtc: String = "2026-09-29T00:00:00.000Z"

    @Volatile var failSearchWith: HttpStatusCode? = null

    val received: MutableList<Received> = CopyOnWriteArrayList()

    private val logins = AtomicInteger()

    @Volatile private var validJwt: String? = null

    /** How many times the bridge logged in successfully. */
    val loginCount: Int get() = logins.get()

    /** The JWT issued last is no longer accepted: the next protected call answers 401. */
    fun expireJwt() {
        validJwt = "expired-${System.nanoTime()}"
    }

    private val server: EmbeddedServer<*, *> =
        embeddedServer(CIO, host = HOST, port = 0) {
            routing {
                post("$API/login") {
                    val body = call.record()
                    if (!call.apiKeyOk()) return@post
                    val json = JSON.parseToJsonElement(body) as JsonObject
                    if (json["username"]?.jsonPrimitive?.content != username ||
                        json["password"]?.jsonPrimitive?.content != password
                    ) {
                        call.answer(
                            "{\"message\":\"Error, invalid username/password\",\"status\":401}",
                            HttpStatusCode.Unauthorized,
                        )
                        return@post
                    }
                    val jwt = "jwt-${logins.incrementAndGet()}"
                    validJwt = jwt
                    call.answer(
                        "{\"user\":{\"allowed_downloads\":$allowed,\"level\":\"Sub leecher\",\"vip\":false}," +
                            "\"base_url\":\"api.opensubtitles.com\",\"token\":\"$jwt\",\"status\":200}",
                        HttpStatusCode.OK,
                    )
                }
                get("$API/subtitles") {
                    call.record()
                    if (!call.apiKeyOk()) return@get
                    failSearchWith?.let {
                        call.answer("{\"message\":\"refused\"}", it)
                        return@get
                    }
                    val params = call.request.queryParameters
                    val languages = params["languages"].orEmpty().split(',').toSet()
                    val (key, byHash) =
                        when {
                            params["moviehash"] != null -> "hash:${params["moviehash"]}" to true
                            params["imdb_id"] != null -> "imdb:${params["imdb_id"]}" to false
                            else -> "query:${params["query"]}" to false
                        }
                    val found = subtitles[key].orEmpty().filter { it.language in languages }
                    call.answer(searchPage(found, byHash), HttpStatusCode.OK)
                }
                post("$API/download") {
                    val body = call.record()
                    if (!call.apiKeyOk() || !call.jwtOk()) return@post
                    if (remaining <= 0) {
                        call.answer(
                            "{\"requests\":$allowed,\"remaining\":0,\"message\":\"You have downloaded your allowed " +
                                "$allowed subtitles for 24h.\",\"reset_time\":\"07 hours\",\"reset_time_utc\":\"$resetTimeUtc\"}",
                            HttpStatusCode.NotAcceptable,
                        )
                        return@post
                    }
                    val fileId = (JSON.parseToJsonElement(body) as JsonObject).getValue("file_id").jsonPrimitive.long
                    remaining -= 1
                    call.answer(
                        "{\"link\":\"$baseUrl/files/$fileId\",\"file_name\":\"sub-$fileId.srt\"," +
                            "\"requests\":${allowed - remaining},\"remaining\":$remaining," +
                            "\"message\":\"ok\",\"reset_time\":\"07 hours\",\"reset_time_utc\":\"$resetTimeUtc\"}",
                        HttpStatusCode.OK,
                    )
                }
                get("$API/infos/user") {
                    call.record()
                    if (!call.apiKeyOk() || !call.jwtOk()) return@get
                    call.answer(
                        "{\"data\":{\"allowed_downloads\":$allowed,\"downloads_count\":${allowed - remaining}," +
                            "\"remaining_downloads\":$remaining,\"vip\":false}}",
                        HttpStatusCode.OK,
                    )
                }
                get("/files/{id}") {
                    call.record()
                    val bytes = call.parameters["id"]?.toLongOrNull()?.let { files[it] }
                    if (bytes == null) {
                        call.answer("{\"message\":\"gone\"}", HttpStatusCode.NotFound)
                        return@get
                    }
                    call.respondBytes(bytes, ContentType.Application.OctetStream)
                }
            }
        }.start(wait = false)

    /** The root of the fake: the API lives at [apiUrl]. */
    val baseUrl: String by lazy {
        val port =
            runBlocking {
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port
            }
        "http://$HOST:$port"
    }

    /** What the bridge takes as its OpenSubtitles base URL. */
    val apiUrl: String get() = baseUrl + API

    override fun close() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MILLIS)
    }

    private fun searchPage(
        found: List<Sub>,
        byHash: Boolean,
    ): String {
        val items =
            found.joinToString(",") { sub ->
                val hashField = if (byHash) ",\"moviehash_match\":${sub.hashMatch}" else ""
                "{\"id\":\"${sub.fileId}\",\"type\":\"subtitle\",\"attributes\":{" +
                    "\"subtitle_id\":\"${sub.fileId}\",\"language\":\"${sub.language}\"," +
                    "\"download_count\":${sub.downloadCount},\"hearing_impaired\":${sub.hearingImpaired}," +
                    "\"hd\":true,\"fps\":23.976,\"ratings\":0.0,\"from_trusted\":${sub.fromTrusted}," +
                    "\"foreign_parts_only\":${sub.foreignPartsOnly},\"ai_translated\":${sub.aiTranslated}," +
                    "\"machine_translated\":${sub.machineTranslated},\"release\":\"release-${sub.fileId}\"$hashField," +
                    "\"files\":[{\"file_id\":${sub.fileId},\"cd_number\":1,\"file_name\":\"file-${sub.fileId}\"}]}}"
            }
        return "{\"total_pages\":1,\"total_count\":${found.size},\"per_page\":60,\"page\":1,\"data\":[$items]}"
    }

    private suspend fun ApplicationCall.record(): String {
        val body = receiveText()
        received +=
            Received(
                method = request.httpMethod.value,
                path = request.path(),
                query = request.local.uri.substringAfter('?', ""),
                apiKey = request.headers[API_KEY_HEADER],
                userAgent = request.headers[HttpHeaders.UserAgent],
                authorization = request.headers[HttpHeaders.Authorization],
                body = body,
            )
        return body
    }

    private suspend fun ApplicationCall.apiKeyOk(): Boolean {
        if (request.headers[API_KEY_HEADER] == apiKey) return true
        answer("{\"message\":\"You cannot consume this service\"}", HttpStatusCode.Forbidden)
        return false
    }

    private suspend fun ApplicationCall.jwtOk(): Boolean {
        val jwt = validJwt
        if (jwt != null && request.headers[HttpHeaders.Authorization] == "Bearer $jwt") return true
        answer("{\"message\":\"Unauthorized\"}", HttpStatusCode.Unauthorized)
        return false
    }

    private suspend fun ApplicationCall.answer(
        body: String,
        status: HttpStatusCode,
    ) {
        respondText(body, ContentType.Application.Json, status)
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val API = "/api/v1"
        const val API_KEY_HEADER = "Api-Key"
        const val STOP_TIMEOUT_MILLIS = 1_000L
        val JSON = Json
    }
}
