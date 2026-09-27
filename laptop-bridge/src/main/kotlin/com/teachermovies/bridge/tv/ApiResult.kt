package com.teachermovies.bridge.tv

/**
 * Outcome of a [TvApi] call that has no call-specific result (#271). No [TvApi] method throws: a
 * command always gets a value it can turn into a message and an exit code.
 */
sealed interface ApiResult<out T> {
    data class Success<T>(
        val value: T,
    ) : ApiResult<T>

    data class Failure(
        val failure: ApiFailure,
    ) : ApiResult<Nothing>
}

/** Why a [TvApi] call did not succeed. The same three cases the phone's client models (#196). */
sealed interface ApiFailure {
    /** Any 401 on a protected call: the token is missing, revoked, or the TV forgot this laptop. */
    data object Unauthorized : ApiFailure

    /**
     * Any other non-2xx answer. [code] and [message] come from the TV's
     * `{"error":"<code>","message":"..."}` body when it sent one, otherwise they are null.
     */
    data class Http(
        val status: Int,
        val code: String?,
        val message: String?,
    ) : ApiFailure

    /**
     * The TV could not be reached, or did not answer as documented: connection refused, timeout,
     * unknown host, a malformed body. [reason] is a short diagnostic that never holds a token, a PIN
     * or a response body.
     */
    data class Network(
        val reason: String,
    ) : ApiFailure
}

/** Outcome of [TvApi.pair] (`POST /api/pair` with `scope: "bridge"`, ADR-0005 §1 and §4). */
sealed interface PairOutcome {
    /** 200: [token] is the bridge's bearer token, issued with [scope] (which must be `bridge`). */
    data class Paired(
        val token: String,
        val scope: String,
    ) : PairOutcome {
        override fun toString(): String = "Paired(token=<redacted>, scope=$scope)"
    }

    /** 401 `wrong_pin`. */
    data object WrongPin : PairOutcome

    /** 429 `too_many_attempts`: the TV has stopped accepting PINs for a while. */
    data object TooManyAttempts : PairOutcome

    /**
     * 200, but [issued] is not `bridge`: a TV from before scoped tokens (#270) issues phone tokens,
     * which reach neither `/api/bridge` nor `/api/logs`. Nothing is stored in this case.
     */
    data class WrongScope(
        val issued: String?,
    ) : PairOutcome

    data class Failed(
        val failure: ApiFailure,
    ) : PairOutcome
}
