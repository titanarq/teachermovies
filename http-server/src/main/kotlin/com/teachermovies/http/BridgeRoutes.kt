package com.teachermovies.http

import com.teachermovies.bridge.protocol.BridgeCancelDto
import com.teachermovies.bridge.protocol.BridgeJobDto
import com.teachermovies.bridge.protocol.BridgeJobProtocol
import com.teachermovies.bridge.protocol.BridgeJobResultDto
import com.teachermovies.http.auth.TokenScope
import com.teachermovies.http.auth.requireBearer
import com.teachermovies.http.bridge.BridgeStreamEvent
import com.teachermovies.http.bridge.CompleteResult
import com.teachermovies.http.bridge.bridgeJson
import com.teachermovies.http.sse.SseFormat
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import java.io.IOException

/** `: ping` cadence of the job stream, the same as `GET /api/events`' (#62). */
private const val PING_INTERVAL_MS = 15_000L

/**
 * `GET /api/bridge/jobs` and `POST /api/bridge/jobs/{id}/result` (#275, ADR-0005 §2): the laptop
 * bridge's side of [ServerDeps.bridge]. Bridge tokens only, in the `Authorization` header only
 * (ADR-0005 §4): a phone token or a `?token=` is 401.
 */
internal fun Route.bridgeRoutes(deps: ServerDeps) {
    requireBearer(deps.pairing, setOf(TokenScope.BRIDGE)) {
        get("/api/bridge/jobs") {
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondTextWriter(ContentType.Text.EventStream) {
                // Opened only once the response has started: from here on the hub sends jobs here.
                val stream = deps.bridge.connect()
                try {
                    coroutineScope {
                        // The first ping goes out at once, so the bridge sees the stream is live.
                        val pingJob =
                            launch {
                                while (isActive) {
                                    write(SseFormat.PING)
                                    flush()
                                    delay(PING_INTERVAL_MS)
                                }
                            }
                        try {
                            for (event in stream.events) {
                                write(event.toFrame())
                                flush()
                            }
                        } finally {
                            pingJob.cancel()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    // The bridge went away mid-write; end quietly (never log the token, AGENTS.md).
                } finally {
                    deps.bridge.disconnect(stream)
                }
            }
        }
        post("/api/bridge/jobs/{id}/result") {
            val id = call.parameters["id"].orEmpty()
            val bytes = call.receiveChannel().readRemaining(BridgeJobProtocol.MAX_PAYLOAD_BYTES + 1L).readByteArray()
            if (bytes.size > BridgeJobProtocol.MAX_PAYLOAD_BYTES) {
                call.respondApiError(
                    HttpStatusCode.BadRequest,
                    ApiError("too_large", "A result is at most ${BridgeJobProtocol.MAX_PAYLOAD_BYTES} bytes"),
                )
                return@post
            }
            val result =
                try {
                    bridgeJson.decodeFromString(BridgeJobResultDto.serializer(), bytes.decodeToString())
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            if (result == null) {
                call.respondApiError(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        "bad_request",
                        "Body must be {\"status\":\"ok\",\"text\":...} or {\"status\":\"error\",\"code\":...}",
                    ),
                )
                return@post
            }
            when (deps.bridge.complete(id, result)) {
                CompleteResult.ACCEPTED -> {
                    call.respond(HttpStatusCode.NoContent)
                }

                CompleteResult.UNKNOWN -> {
                    call.respondApiError(HttpStatusCode.NotFound, ApiError("unknown_job", "No such job"))
                }

                CompleteResult.CLOSED -> {
                    call.respondApiError(
                        HttpStatusCode.Conflict,
                        ApiError("job_closed", "The job already ended (answered, replaced, timed out or cancelled)"),
                    )
                }
            }
        }
    }
}

private fun BridgeStreamEvent.toFrame(): String =
    when (this) {
        is BridgeStreamEvent.Job -> {
            SseFormat.event(BridgeJobProtocol.JOB_EVENT, bridgeJson.encodeToString(BridgeJobDto.serializer(), job))
        }

        is BridgeStreamEvent.Cancel -> {
            SseFormat.event(
                BridgeJobProtocol.CANCEL_EVENT,
                bridgeJson.encodeToString(BridgeCancelDto.serializer(), BridgeCancelDto(id)),
            )
        }
    }
