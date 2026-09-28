package com.teachermovies.bridge.tv

import com.teachermovies.bridge.protocol.LogEntryDto
import com.teachermovies.bridge.protocol.LogsPageDto
import com.teachermovies.bridge.protocol.SubtitleNeedDto
import com.teachermovies.bridge.protocol.SubtitleStatusDto
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
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A real Ktor CIO server on a loopback port standing in for the TV (#271): `POST /api/pair` with
 * scoped tokens (#270), `GET /api/status` (ADR-0002), `GET /api/logs` and (#272) `GET /api/logs/stream`
 * -- a `boot` frame, the [logsPage] entries after `since`, then whatever [sendLog] queues -- (ADR-0006 §4), spelled the
 * way `docs/modules/http-server.md` documents them and serving the log DTOs of `:bridge-protocol`.
 *
 * It is hand-written rather than the real `:http-server` module because that one is an Android
 * library: depending on it would pull the TV, and the Ktor server, into the laptop program. Every
 * request is recorded so a test can assert what the bridge actually sent -- including that a token
 * travelled in the `Authorization` header and nowhere else.
 *
 * The knobs are the states a test needs: [pin] and [token] decide what is accepted, [issuedScope]
 * what the TV stamps on the token it issues, and [failWith] makes every route answer one error.
 */
class FakeTv : Closeable {
    /** One request the bridge made, as the TV saw it. */
    data class Received(
        val method: String,
        val path: String,
        val query: String,
        val authorization: String?,
        val body: String,
    )

    /** One canned error every route answers, until a test sets [failWith] back to null. */
    data class Answer(
        val status: HttpStatusCode,
        val code: String,
    )

    /** The PIN this TV shows and accepts at pairing. */
    @Volatile
    var pin: String = DEFAULT_PIN

    /** The token it issues, and afterwards accepts in `Authorization: Bearer`. */
    @Volatile
    var token: String = DEFAULT_TOKEN

    /** The scope it stamps on the token it issues; null omits the field, as a pre-#270 TV did. */
    @Volatile
    var issuedScope: String? = BRIDGE_SCOPE

    /** `GET /api/status`'s body. */
    @Volatile
    var statusJson: String = DEFAULT_STATUS_JSON

    /** `GET /api/logs`'s body, encoded with the shared DTOs. */
    @Volatile
    var logsPage: LogsPageDto = DEFAULT_LOGS_PAGE

    /** When not null, every route answers this instead of doing its job. */
    @Volatile
    var failWith: Answer? = null

    val received: MutableList<Received> = CopyOnWriteArrayList()

    /** What `POST /api/bridge/jobs/{id}/result` answers (after the token check); 204 by default. */
    @Volatile
    var resultAnswer: Answer? = null

    /**
     * The TV's subtitle needs (#280), served by `GET /api/bridge/subtitle-needs` in this order. Like
     * the real TV, a `searching` status takes a need off the list and a `failed` one puts it back
     * (with state `failed`); `not_found` and an upload leave it off.
     */
    val subtitleNeeds: MutableList<SubtitleNeedDto> = CopyOnWriteArrayList()

    /** Every `POST /api/bridge/subtitle-status` body accepted, in order. */
    val subtitleStatuses: MutableList<SubtitleStatusDto> = CopyOnWriteArrayList()

    /** When not null, what `POST /api/bridge/subtitle-status` answers instead of accepting it. */
    @Volatile
    var statusAnswer: Answer? = null

    /** What `POST /api/bridge/subtitles` answers (after the token check); 201 by default. */
    @Volatile
    var uploadAnswer: Answer? = null

    /** Frames still to write on each open `GET /api/bridge/jobs` stream, newest last. */
    private val streams: MutableList<Channel<String>> = CopyOnWriteArrayList()

    /** Needs a `searching` status took off [subtitleNeeds], until answered. */
    private val claimed: MutableList<SubtitleNeedDto> = CopyOnWriteArrayList()

    /** How many job streams the TV has accepted so far. */
    val streamsOpened = AtomicInteger()

    /** Queues one SSE frame on the newest open job stream; false when none is open. */
    fun sendFrame(
        event: String,
        data: String,
    ): Boolean = streams.lastOrNull()?.trySend("event: $event\ndata: $data\n\n")?.isSuccess ?: false

    /** Ends every open job stream from the TV side, the way a restart or a newer stream would. */
    fun closeStreams() {
        streams.forEach { it.close() }
        streams.clear()
        logStreams.forEach { it.close() }
        logStreams.clear()
    }

    /** Frames still to write on each open `GET /api/logs/stream`, newest last (#272). */
    private val logStreams: MutableList<Channel<String>> = CopyOnWriteArrayList()

    /** Queues one live `log` frame for [entry] on the newest open log stream; false when none is open. */
    fun sendLog(entry: LogEntryDto): Boolean =
        logStreams
            .lastOrNull()
            ?.trySend(
                "event: log\ndata: ${JSON.encodeToString(LogEntryDto.serializer(), entry)}\n\n",
            )?.isSuccess
            ?: false

    /** How many log streams are open right now. */
    val openLogStreams: Int
        get() = logStreams.size

    private val server: EmbeddedServer<*, *> =
        embeddedServer(CIO, host = HOST, port = 0) {
            routing {
                post("/api/pair") {
                    val body = call.record()
                    if (call.refuse()) return@post
                    if (body.jsonField("pin") != pin) {
                        call.error(HttpStatusCode.Unauthorized, "wrong_pin", "Wrong PIN")
                        return@post
                    }
                    val scope = issuedScope?.let { ",\"scope\":\"$it\"" } ?: ""
                    call.answer("{\"token\":\"$token\"$scope}", HttpStatusCode.OK)
                }
                get("/api/status") {
                    call.record()
                    if (call.refuse()) return@get
                    call.answer(statusJson, HttpStatusCode.OK)
                }
                get("/api/logs") {
                    call.record()
                    if (call.refuse()) return@get
                    if (call.request.headers[HttpHeaders.Authorization] != "Bearer $token") {
                        call.error(HttpStatusCode.Unauthorized, "unauthorized", "Missing or invalid bearer token")
                        return@get
                    }
                    call.answer(JSON.encodeToString(LogsPageDto.serializer(), logsPage), HttpStatusCode.OK)
                }
                get("/api/bridge/jobs") {
                    call.record()
                    if (call.refuse()) return@get
                    if (!call.authorized()) return@get
                    val frames = Channel<String>(Channel.UNLIMITED)
                    streams += frames
                    streamsOpened.incrementAndGet()
                    call.response.header(HttpHeaders.CacheControl, "no-cache")
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        write(": ping\n\n")
                        flush()
                        for (frame in frames) {
                            write(frame)
                            flush()
                        }
                    }
                }
                get("/api/logs/stream") {
                    call.record()
                    if (call.refuse()) return@get
                    if (!call.authorized()) return@get
                    val since = call.request.queryParameters["since"]?.toLong() ?: 0L
                    val page = logsPage
                    val frames = Channel<String>(Channel.UNLIMITED)
                    logStreams += frames
                    call.response.header(HttpHeaders.CacheControl, "no-cache")
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        write("event: boot\ndata: {\"bootId\":\"${page.bootId}\"}\n\n")
                        for (entry in page.entries.filter { it.seq > since }) {
                            write("event: log\ndata: ${JSON.encodeToString(LogEntryDto.serializer(), entry)}\n\n")
                        }
                        write(": ping\n\n")
                        flush()
                        for (frame in frames) {
                            write(frame)
                            flush()
                        }
                    }
                }
                post("/api/bridge/jobs/{id}/result") {
                    call.record()
                    if (call.refuse()) return@post
                    if (!call.authorized()) return@post
                    val answer = resultAnswer
                    if (answer != null) {
                        call.error(answer.status, answer.code, "refused by the fake TV")
                    } else {
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
                get("/api/bridge/subtitle-needs") {
                    call.record()
                    if (call.refuse()) return@get
                    if (!call.authorized()) return@get
                    val body = JSON.encodeToString(ListSerializer(SubtitleNeedDto.serializer()), subtitleNeeds.toList())
                    call.answer(body, HttpStatusCode.OK)
                }
                post("/api/bridge/subtitle-status") {
                    val body = call.record()
                    if (call.refuse()) return@post
                    if (!call.authorized()) return@post
                    statusAnswer?.let {
                        call.error(it.status, it.code, "refused by the fake TV")
                        return@post
                    }
                    val status = JSON.decodeFromString(SubtitleStatusDto.serializer(), body)
                    val need =
                        subtitleNeeds.firstOrNull {
                            it.torrentId == status.torrentId && it.language == status.language
                        } ?: claimed.firstOrNull { it.torrentId == status.torrentId && it.language == status.language }
                    if (need == null) {
                        call.error(HttpStatusCode.NotFound, "unknown_need", "No such need")
                        return@post
                    }
                    subtitleStatuses += status
                    when (status.status) {
                        "searching" -> {
                            subtitleNeeds.remove(need)
                            claimed += need.copy(attempts = need.attempts + 1)
                        }

                        "failed" -> {
                            claimed.removeIf { it.torrentId == need.torrentId && it.language == need.language }
                            subtitleNeeds += need.copy(state = "failed")
                        }

                        else -> {
                            claimed.removeIf { it.torrentId == need.torrentId && it.language == need.language }
                        }
                    }
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/api/bridge/subtitles") {
                    call.record()
                    if (call.refuse()) return@post
                    if (!call.authorized()) return@post
                    val answer = uploadAnswer
                    if (answer != null) {
                        call.error(answer.status, answer.code, "refused by the fake TV")
                    } else {
                        call.answer("{\"path\":\"subs/movie.srt\"}", HttpStatusCode.Created)
                    }
                }
                route("{...}") {
                    handle {
                        call.record()
                        call.error(HttpStatusCode.NotFound, "not_found", "Not found")
                    }
                }
            }
        }.start(wait = false)

    /** The URL a test points the bridge at; the port is the one the OS gave the server. */
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

    override fun close() {
        closeStreams()
        server.stop(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MILLIS)
    }

    /** Records [call] as received and returns its body, which Ktor only hands out once. */
    private suspend fun ApplicationCall.record(): String {
        val body = receiveText()
        received +=
            Received(
                method = request.httpMethod.value,
                path = request.path(),
                query = request.local.uri.substringAfter('?', ""),
                authorization = request.headers[HttpHeaders.Authorization],
                body = body,
            )
        return body
    }

    /** Answers 401 unless the call carries this TV's token, and says whether it did not. */
    private suspend fun ApplicationCall.authorized(): Boolean {
        if (request.headers[HttpHeaders.Authorization] == "Bearer $token") return true
        error(HttpStatusCode.Unauthorized, "unauthorized", "Missing or invalid bearer token")
        return false
    }

    /** Answers [failWith] when a test set one, and says whether it did. */
    private suspend fun ApplicationCall.refuse(): Boolean {
        val answer = failWith ?: return false
        error(answer.status, answer.code, "refused by the fake TV")
        return true
    }

    private suspend fun ApplicationCall.error(
        status: HttpStatusCode,
        code: String,
        message: String,
    ) {
        answer("{\"error\":\"$code\",\"message\":\"$message\"}", status)
    }

    private suspend fun ApplicationCall.answer(
        body: String,
        status: HttpStatusCode,
    ) {
        respondText(body, ContentType.Application.Json, status)
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val BRIDGE_SCOPE = "bridge"
        const val STOP_TIMEOUT_MILLIS = 1_000L
        const val DEFAULT_PIN = "482916"
        const val DEFAULT_TOKEN = "tok_super-secreto_9f3a"
        const val DEFAULT_STATUS_JSON =
            """{"version":"1.0","engine":"running","freeBytes":null,"totalBytes":null,"torrents":3}"""

        val JSON = Json { explicitNulls = true }

        val DEFAULT_LOGS_PAGE =
            LogsPageDto(
                bootId = "boot-1234abcd",
                entries =
                    listOf(
                        LogEntryDto(
                            seq = 7L,
                            timeMs = 1_700_000_000_000L,
                            level = "info",
                            module = "torrent",
                            message = "descarga completada",
                        ),
                        LogEntryDto(
                            seq = 8L,
                            timeMs = 1_700_000_001_000L,
                            level = "warn",
                            module = "player",
                            message = "underrun de 240 ms",
                        ),
                    ),
            )
    }
}
