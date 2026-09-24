package com.teachermovies.http.auth

import com.teachermovies.http.ApiError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext

/** Name of the query parameter that may carry the token, on `/api/events` only (ADR-0002). */
const val TOKEN_QUERY_PARAMETER = "token"

/**
 * Routes declared in [build] require a token issued by [pairing] (ADR-0002).
 *
 * The token is read from `Authorization: Bearer <token>`. Only when [allowQueryToken] is true (used
 * solely by `GET /api/events`, whose `EventSource` client cannot set headers) is `?token=<token>`
 * also accepted; everywhere else a query-param token alone is rejected. A missing or invalid token
 * is 401 `{"error":"unauthorized"}` with `WWW-Authenticate: Bearer`, and the handler never runs.
 * The token is never echoed in the response.
 */
fun Route.requireBearer(
    pairing: PairingManager,
    allowQueryToken: Boolean = false,
    build: Route.() -> Unit,
): Route {
    val route = createChild(BearerRouteSelector(allowQueryToken))
    route.install(
        createRouteScopedPlugin("RequireBearer") {
            onCall { call ->
                val token = call.bearerToken(allowQueryToken)
                if (token == null || !pairing.isValid(token)) {
                    call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ApiError("unauthorized", "Missing or invalid bearer token"),
                    )
                }
            }
        },
    )
    route.build()
    return route
}

private fun ApplicationCall.bearerToken(allowQueryToken: Boolean): String? {
    val header = request.headers[HttpHeaders.Authorization]
    if (header != null) {
        val parts = header.trim().split(' ', limit = 2)
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return null
        return parts[1].trim().ifEmpty { null }
    }
    if (!allowQueryToken) return null
    return request.queryParameters[TOKEN_QUERY_PARAMETER]?.ifEmpty { null }
}

/**
 * Transparent selector that only groups the protected routes. A plain class (not a data class), so
 * every [requireBearer] block gets its own child route and its own plugin instance.
 */
private class BearerRouteSelector(
    private val allowQueryToken: Boolean,
) : RouteSelector() {
    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ): RouteSelectorEvaluation = RouteSelectorEvaluation.Transparent

    override fun toString(): String = "(requireBearer${if (allowQueryToken) " +query" else ""})"
}

/**
 * [uri] with the value of every `token` query parameter replaced by `<redacted>`, for any request
 * logging. The `Authorization` header must never be logged at all (AGENTS.md: never log tokens).
 */
fun redactTokenQuery(uri: String): String =
    uri.replace(Regex("([?&]$TOKEN_QUERY_PARAMETER=)[^&#]*")) { it.groupValues[1] + "<redacted>" }
