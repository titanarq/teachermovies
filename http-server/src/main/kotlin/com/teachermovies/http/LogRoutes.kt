package com.teachermovies.http

import com.teachermovies.bridge.protocol.LogEntryDto
import com.teachermovies.bridge.protocol.LogStreamBootDto
import com.teachermovies.bridge.protocol.LogsPageDto
import com.teachermovies.core.log.LogEntry
import com.teachermovies.core.log.LogLevel
import com.teachermovies.core.log.RingBufferLogSink
import com.teachermovies.http.auth.TokenScope
import com.teachermovies.http.auth.requireBearer
import com.teachermovies.http.dto.toDto
import com.teachermovies.http.sse.SseFormat
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import java.io.IOException

/** `: ping` cadence of the stream, the same as `GET /api/events`' (#62). */
private const val PING_INTERVAL_MS = 15_000L

// Nullable fields are always written, matching the ContentNegotiation config in Module.kt, so
// every field of a DTO appears in every page and frame.
private val logsJson = Json { explicitNulls = true }

/** Parsed query of the log routes: a seq cursor, a minimum level (null = every level), a page size. */
private data class LogsQuery(
    val since: Long,
    val minLevel: LogLevel?,
    val limit: Int,
)

/**
 * `GET /api/logs` and `GET /api/logs/stream` (#269, ADR-0006 §4): the TV's redacting ring buffer
 * ([ServerDeps.logs]) as a JSON page and as an SSE stream, with the DTOs of `:bridge-protocol`
 * (ADR-0005 §1). Both accept phone and bridge tokens in the `Authorization` header only -- the
 * `?token=` exception of ADR-0002 stays exclusive to `GET /api/events` (the phone web reads the
 * stream with `fetch`, #273).
 */
internal fun Route.logRoutes(deps: ServerDeps) {
    // Both token scopes reach the logs (ADR-0005 §4): the phone web UI (#273) and the laptop
    // bridge's mirror (#272).
    requireBearer(deps.pairing, setOf(TokenScope.PHONE, TokenScope.BRIDGE)) {
        get("/api/logs") {
            val query = call.logsQuery(withLimit = true) ?: return@get
            val entries = deps.logs.entries(since = query.since, minLevel = query.minLevel, limit = query.limit)
            call.respond(LogsPageDto(bootId = deps.logs.bootId, entries = entries.map { it.toDto() }))
        }
        get("/api/logs/stream") {
            val query = call.logsQuery(withLimit = false) ?: return@get
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondTextWriter(ContentType.Text.EventStream) {
                try {
                    coroutineScope {
                        val sink = deps.logs
                        val minLevel = query.minLevel
                        val pingJob =
                            launch {
                                while (isActive) {
                                    delay(PING_INTERVAL_MS)
                                    write(SseFormat.PING)
                                    flush()
                                }
                            }
                        // The live queue is armed (and yielded to, so the collector has had its
                        // turn to subscribe) before the backlog below is read: a line recorded
                        // while this stream starts up reaches the writer through one path or the
                        // other, and the seq cursor drops what both delivered. The sink's own
                        // live flow has no replay -- a collector that attaches late sees nothing.
                        val live = Channel<LogEntry>(Channel.UNLIMITED)
                        val collector = launch { sink.entries.collect { live.send(it) } }
                        yield()
                        try {
                            var cursor = query.since
                            write(bootEvent(sink.bootId))
                            val backlog =
                                sink.entries(
                                    since = cursor,
                                    minLevel = minLevel,
                                    limit = RingBufferLogSink.MAX_LINES,
                                )
                            for (entry in backlog) {
                                write(logEvent(entry))
                                cursor = entry.seq
                            }
                            flush()
                            for (entry in live) {
                                if (entry.seq <= cursor) continue
                                if (minLevel != null && !entry.level.isAtLeast(minLevel)) continue
                                cursor = entry.seq
                                write(logEvent(entry))
                                flush()
                            }
                        } finally {
                            collector.cancel()
                            pingJob.cancel()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    // The client disconnected mid-write; end the stream quietly, as
                    // `GET /api/events` does (never log the token, AGENTS.md).
                }
            }
        }
    }
}

private fun bootEvent(bootId: String): String =
    SseFormat.event("boot", logsJson.encodeToString(LogStreamBootDto.serializer(), LogStreamBootDto(bootId)))

private fun logEvent(entry: LogEntry): String =
    SseFormat.event("log", logsJson.encodeToString(LogEntryDto.serializer(), entry.toDto()))

/**
 * Parses the query of `GET /api/logs` and, with `withLimit = false`, of `GET /api/logs/stream`
 * (the stream replays the whole backlog and ignores `limit`): `since` is a [LogEntry.seq] cursor
 * (default 0, the whole buffer), `level` a lower-case minimum (default every stored level) and
 * `limit` the page size (default [RingBufferLogSink.DEFAULT_PAGE], at most the buffer's own
 * [RingBufferLogSink.MAX_LINES]). Responds 400 `bad_request` on the first invalid parameter and
 * returns null.
 */
private suspend fun ApplicationCall.logsQuery(withLimit: Boolean): LogsQuery? {
    val parameters = request.queryParameters

    val sinceText = parameters["since"]
    val since = sinceText?.toLongOrNull()
    if (sinceText != null && (since == null || since < 0L)) {
        respondApiError(
            HttpStatusCode.BadRequest,
            ApiError("bad_request", "since must be a non-negative integer"),
        )
        return null
    }

    val levelText = parameters["level"]
    val minLevel = levelText?.let { text -> LogLevel.entries.firstOrNull { it.name.lowercase() == text } }
    if (levelText != null && minLevel == null) {
        respondApiError(
            HttpStatusCode.BadRequest,
            ApiError("bad_request", "level must be debug, info, warn or error"),
        )
        return null
    }

    val limitText = if (withLimit) parameters["limit"] else null
    val limit = limitText?.toIntOrNull()
    if (limitText != null && (limit == null || limit !in 1..RingBufferLogSink.MAX_LINES)) {
        respondApiError(
            HttpStatusCode.BadRequest,
            ApiError("bad_request", "limit must be between 1 and ${RingBufferLogSink.MAX_LINES}"),
        )
        return null
    }

    return LogsQuery(
        since = since ?: 0L,
        minLevel = minLevel,
        limit = limit ?: RingBufferLogSink.DEFAULT_PAGE,
    )
}
