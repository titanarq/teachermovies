package com.teachermovies.http

import com.teachermovies.core.model.TorrentId
import com.teachermovies.http.auth.requireBearer
import com.teachermovies.http.dto.toDto
import com.teachermovies.torrent.api.EngineResult
import com.teachermovies.torrent.api.TorrentSnapshot
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Read-only `/api/torrents*` routes (#57): list every torrent, one torrent, and its file list. All
 * three require a bearer token (ADR-0002); mutating routes (`POST`/`PUT`/`DELETE`) are #60.
 */
internal fun Route.torrentReadRoutes(deps: ServerDeps) {
    requireBearer(deps.pairing) {
        get("/api/torrents") {
            call.respond(deps.engine.torrents.value.map { it.toDto() })
        }
        get("/api/torrents/{id}") {
            val id = call.torrentIdOrRespond() ?: return@get
            val snapshot = deps.snapshotOf(id) ?: return@get call.respondUnknownTorrent()

            call.respond(snapshot.toDto())
        }
        get("/api/torrents/{id}/files") {
            val id = call.torrentIdOrRespond() ?: return@get
            val snapshot = deps.snapshotOf(id) ?: return@get call.respondUnknownTorrent()
            if (!snapshot.hasMetadata) {
                call.respondApiError(HttpStatusCode.Conflict, ApiError("not_ready", "Torrent metadata not available yet"))
                return@get
            }

            when (val result = deps.engine.files(id)) {
                is EngineResult.Ok -> call.respond(result.value.map { it.toDto() })
                is EngineResult.Failure -> call.respondUnknownTorrent()
            }
        }
    }
}

internal fun ServerDeps.snapshotOf(id: TorrentId): TorrentSnapshot? = engine.torrents.value.firstOrNull { it.id == id }

/** Parses the `{id}` path parameter, responding 400 `invalid_id` and returning `null` if it is not a valid [TorrentId]. */
internal suspend fun ApplicationCall.torrentIdOrRespond(): TorrentId? =
    try {
        TorrentId(parameters["id"].orEmpty())
    } catch (_: IllegalArgumentException) {
        respondApiError(HttpStatusCode.BadRequest, ApiError("invalid_id", "Not a valid torrent id"))
        null
    }

internal suspend fun ApplicationCall.respondUnknownTorrent() {
    respondApiError(HttpStatusCode.NotFound, ApiError("unknown_torrent", "No such torrent"))
}
