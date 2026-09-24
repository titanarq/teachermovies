package com.teachermovies.http

import com.teachermovies.http.auth.PairResult
import com.teachermovies.http.auth.PairingManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.ContentTransformationException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** Body of `POST /api/pair`. [deviceName] is informational; nothing stores it yet. */
@Serializable
data class PairRequest(
    val pin: String,
    val deviceName: String? = null,
)

/** Success body of `POST /api/pair`. */
@Serializable
data class PairResponse(
    val token: String,
)

/**
 * `POST /api/pair` (public, ADR-0002): exchanges the PIN shown on the TV for a bearer token.
 * 200 `{"token":"..."}` | 400 `bad_request` | 401 `wrong_pin` | 429 `too_many_attempts`.
 * Neither the PIN nor the token ever appears in an error body.
 */
internal fun Route.pairRoutes(pairing: PairingManager) {
    post("/api/pair") {
        val request =
            try {
                call.receive<PairRequest>()
            } catch (_: ContentTransformationException) {
                null
            } catch (_: BadRequestException) {
                null
            }
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "Expected {\"pin\":\"...\"}"))
            return@post
        }
        when (val result = pairing.pair(request.pin)) {
            is PairResult.Paired -> call.respond(PairResponse(result.token))
            PairResult.WrongPin ->
                call.respond(HttpStatusCode.Unauthorized, ApiError("wrong_pin", "Wrong PIN"))
            PairResult.TooManyAttempts ->
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    ApiError("too_many_attempts", "Too many wrong PINs; try again in a minute"),
                )
        }
    }
}
