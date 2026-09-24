package com.teachermovies.http

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

/**
 * The whole HTTP API: plugins and routes. [LocalHttpServer] runs it on a socket; tests run it
 * in-process with `testApplication { application { module(fakeDeps) } }`.
 */
fun Application.module(deps: ServerDeps) {
    install(ContentNegotiation) {
        // Nullable fields are always written (e.g. `"freeBytes":null`), so clients see every key.
        json(Json { explicitNulls = true })
    }
    install(StatusPages) {
        exception<Throwable> { call, _ ->
            // No stack trace or exception message in the body: it may carry internal details.
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError("internal", "Internal server error"),
            )
        }
        status(HttpStatusCode.NotFound) { call, status ->
            if (call.request.path().isApiPath()) {
                call.respond(status, ApiError("not_found", "No such endpoint"))
            }
        }
    }
    routing {
        statusRoutes(deps)
        pairRoutes(deps.pairing)
        // Every other /api/* route goes inside `requireBearer(deps.pairing) { ... }` (ADR-0002).
    }
}

private fun String.isApiPath(): Boolean = this == "/api" || startsWith("/api/")
