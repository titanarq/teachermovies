package com.teachermovies.http

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/** Body of `GET /api/status`; [freeBytes]/[totalBytes] are `null` when the volume's space is unknown. */
@Serializable
data class StatusResponse(
    val version: String,
    val engine: String,
    val freeBytes: Long?,
    val totalBytes: Long?,
    val torrents: Int,
)

/** `GET /api/status`: public (no token, ADR-0002), a connection indicator that discloses no content. */
internal fun Route.statusRoutes(deps: ServerDeps) {
    get("/api/status") {
        val space = deps.space()
        call.respond(
            StatusResponse(
                version = deps.appVersion,
                engine = deps.engine.engineStatus.value.name.lowercase(),
                freeBytes = space?.freeBytes,
                totalBytes = space?.totalBytes,
                torrents = deps.engine.torrents.value.size,
            ),
        )
    }
}
