package com.teachermovies.mobile.api

/** Outcome of a [TvApi] call that has no call-specific result (#196). No [TvApi] method throws. */
sealed interface ApiResult<out T> {
    data class Success<T>(
        val value: T,
    ) : ApiResult<T>

    data class Failure(
        val failure: ApiFailure,
    ) : ApiResult<Nothing>
}

/** Why a [TvApi] call did not succeed. */
sealed interface ApiFailure {
    /** Any 401 on a protected call: the token is missing, revoked or belongs to another TV. */
    data object Unauthorized : ApiFailure

    /**
     * Any other non-2xx answer. [code] and [message] come from the server's
     * `{"error":"<code>","message":"..."}` body when it has one, otherwise they are `null`.
     */
    data class Http(
        val status: Int,
        val code: String?,
        val message: String?,
    ) : ApiFailure

    /**
     * The TV could not be reached or did not answer as documented: connection refused, timeout,
     * unknown host, a malformed or unexpected body. [reason] is a short diagnostic; it never holds a
     * token or a PIN.
     */
    data class Network(
        val reason: String,
    ) : ApiFailure
}

/** Outcome of [TvApi.pair] (`POST /api/pair`). */
sealed interface PairOutcome {
    /** 200: [token] is the bearer token for every protected call to this TV. */
    data class Paired(
        val token: String,
    ) : PairOutcome {
        override fun toString(): String = "Paired(token=<redacted>)"
    }

    /** 401 `wrong_pin`. */
    data object WrongPin : PairOutcome

    /** 429 `too_many_attempts`. */
    data object TooManyAttempts : PairOutcome

    data class Failed(
        val failure: ApiFailure,
    ) : PairOutcome
}

/** Outcome of [TvApi.addMagnet] (`POST /api/torrents/magnet`). */
sealed interface AddMagnetOutcome {
    /** 201: the TV accepted the magnet; [state] is the torrent's first state (`fetching_metadata`). */
    data class Added(
        val id: String,
        val state: String,
    ) : AddMagnetOutcome

    /** 409 `already_exists`: the TV already has this torrent, under [id]. */
    data class AlreadyExists(
        val id: String,
    ) : AddMagnetOutcome

    /** 400 `invalid_magnet`. */
    data object InvalidMagnet : AddMagnetOutcome

    data class Failed(
        val failure: ApiFailure,
    ) : AddMagnetOutcome
}
