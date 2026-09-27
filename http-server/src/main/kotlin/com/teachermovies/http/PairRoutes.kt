package com.teachermovies.http

import com.teachermovies.http.auth.PairResult
import com.teachermovies.http.auth.PairingManager
import com.teachermovies.http.auth.TokenScope
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.ContentTransformationException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/**
 * Body of `POST /api/pair`. [deviceName] is informational; nothing stores it yet. [scope] says
 * which kind of client is pairing: `"bridge"` for a laptop bridge, and `"phone"` -- also the value
 * an omitted [scope] means, so a client written before scopes keeps working (ADR-0005 §4). Any
 * other value is refused with a 400. [toString] redacts [pin], so logging a request never prints it.
 */
@Serializable
data class PairRequest(
    val pin: String,
    val deviceName: String? = null,
    val scope: String? = null,
) {
    override fun toString(): String = "PairRequest(pin=<redacted>, deviceName=$deviceName, scope=$scope)"
}

/** Success body of `POST /api/pair`: the token and the scope it was issued with. */
@Serializable
data class PairResponse(
    val token: String,
    val scope: String,
)

/**
 * `POST /api/pair` (public, ADR-0002): exchanges the PIN shown on the TV for a bearer token.
 * 200 `{"token":"...","scope":"phone"}` | 400 `bad_request` | 401 `wrong_pin` | 429
 * `too_many_attempts`. Neither the PIN nor the token ever appears in an error body.
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
        val scope =
            if (request.scope == null) TokenScope.PHONE else TokenScope.fromWireValue(request.scope)
        if (scope == null) {
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", unknownScopeMessage()))
            return@post
        }
        when (val result = pairing.pair(request.pin, scope)) {
            is PairResult.Paired -> {
                call.respond(PairResponse(result.token, scope.wireValue))
            }

            PairResult.WrongPin -> {
                call.respond(HttpStatusCode.Unauthorized, ApiError("wrong_pin", "Wrong PIN"))
            }

            PairResult.TooManyAttempts -> {
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    ApiError("too_many_attempts", "Too many wrong PINs; try again in a minute"),
                )
            }
        }
    }
}

/** The 400 message for a `scope` no [TokenScope] spells; it names the accepted values only. */
private fun unknownScopeMessage(): String =
    "Unknown scope; expected one of ${TokenScope.entries.joinToString(", ") { it.wireValue }}"
