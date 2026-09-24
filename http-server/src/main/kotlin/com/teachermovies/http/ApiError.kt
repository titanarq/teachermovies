package com.teachermovies.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable

/** Body of every error response: `{"error":"<code>","message":"..."}`. */
@Serializable
data class ApiError(
    val error: String,
    val message: String,
)

/**
 * Marks a call whose route handler already sent a specific [ApiError] body, so the shared
 * `status(HttpStatusCode.NotFound)` page in [Application.module] does not overwrite a route's own
 * 404 (e.g. `unknown_torrent`, #57) with the generic `not_found` it uses for a truly unmatched
 * route -- [io.ktor.server.plugins.statuspages.StatusPages] `status` handlers fire on any response
 * with that status code, not only ones the framework produced itself.
 */
private val ApiErrorSent = AttributeKey<Boolean>("ApiErrorSent")

/** Whether [respondApiError] already answered this call; read by the shared 404 status page. */
internal fun ApplicationCall.hasApiErrorResponse(): Boolean = attributes.contains(ApiErrorSent)

/** Sends [error] as the JSON body with [status], flagged so the shared 404 page leaves it alone. */
internal suspend fun ApplicationCall.respondApiError(
    status: HttpStatusCode,
    error: ApiError,
) {
    attributes.put(ApiErrorSent, true)
    respond(status, error)
}
