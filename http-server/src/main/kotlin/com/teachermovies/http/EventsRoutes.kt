package com.teachermovies.http

import com.teachermovies.http.auth.requireBearer
import com.teachermovies.http.dto.toDto
import com.teachermovies.http.sse.SseFormat
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

/** Throttle: at most one `torrents` event per second (acceptance criteria, #62). */
private const val THROTTLE_MS = 1_000L

/** `: ping` cadence, so idle proxies/clients don't time out the connection. */
private const val PING_INTERVAL_MS = 15_000L

// Nullable fields (e.g. etaSeconds) are always written, matching the ContentNegotiation config in
// Module.kt, so every field of TorrentDto appears in every event.
private val eventsJson = Json { explicitNulls = true }

/**
 * `GET /api/events` (#62): pushes `engine.torrents` to the client over Server-Sent Events instead
 * of it polling. Plain `call.respondTextWriter(ContentType.Text.EventStream)` -- no `sse` plugin.
 *
 * Wrapped in [requireBearer] with `allowQueryToken = true` (ADR-0002): the browser `EventSource`
 * API cannot set an `Authorization` header, so this route alone also accepts `?token=`; a missing
 * or invalid token is 401 before any event is written.
 *
 * Sends `event: torrents` with the current snapshot immediately, then again whenever
 * [com.teachermovies.torrent.api.TorrentEngine.torrents] changes, conflated and throttled to at
 * most one event per second; a `: ping` comment every 15 s. The coroutine ends quietly when the
 * client disconnects (an [IOException] writing to a closed connection is expected there, not a
 * failure).
 */
internal fun Route.eventsRoutes(deps: ServerDeps) {
    requireBearer(deps.pairing, allowQueryToken = true) {
        get("/api/events") {
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondTextWriter(ContentType.Text.EventStream) {
                try {
                    coroutineScope {
                        val pingJob =
                            launch {
                                while (isActive) {
                                    delay(PING_INTERVAL_MS)
                                    write(SseFormat.PING)
                                    flush()
                                }
                            }
                        try {
                            deps.engine.torrents
                                .map { snapshots -> snapshots.map { it.toDto() } }
                                .conflate()
                                .transform { list ->
                                    emit(list)
                                    delay(THROTTLE_MS)
                                }.collect { list ->
                                    write(SseFormat.event("torrents", eventsJson.encodeToString(list)))
                                    flush()
                                }
                        } finally {
                            pingJob.cancel()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    // The client disconnected mid-write; end the stream quietly (never log the token, AGENTS.md).
                }
            }
        }
    }
}
