package com.teachermovies.http.auth

/**
 * Which client a bearer token was issued to (ADR-0005 §4).
 *
 * A [PHONE] token reaches the phone's own routes; a [BRIDGE] token reaches only the bridge routes
 * (`/api/bridge`) and the log routes (`/api/logs`), and is refused everywhere else, just as a phone
 * token is refused on the bridge routes. Every protected route states the scopes it accepts with
 * [requireBearer].
 *
 * [wireValue] is the spelling `POST /api/pair` accepts in its `scope` field and echoes back.
 */
enum class TokenScope(
    val wireValue: String,
) {
    PHONE("phone"),
    BRIDGE("bridge"),
    ;

    companion object {
        /**
         * The scope [wireValue] names, or null when it names none -- a null is what makes an
         * unknown `scope` a 400 at `POST /api/pair`.
         */
        fun fromWireValue(wireValue: String?): TokenScope? = entries.firstOrNull { it.wireValue == wireValue }
    }
}
