package com.teachermovies.http

import com.teachermovies.http.auth.requireBearer
import com.teachermovies.http.dto.toDto
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.flow.first

/**
 * `GET /api/library` (#73): the movies ready to watch, newest completed first (the order of
 * [com.teachermovies.core.repo.TorrentRepository.observeLibrary]). Requires a bearer token
 * (ADR-0002).
 */
internal fun Route.libraryRoutes(deps: ServerDeps) {
    requireBearer(deps.pairing) {
        get("/api/library") {
            call.respond(
                deps.library
                    .observeLibrary()
                    .first()
                    .map { it.toDto() },
            )
        }
    }
}
