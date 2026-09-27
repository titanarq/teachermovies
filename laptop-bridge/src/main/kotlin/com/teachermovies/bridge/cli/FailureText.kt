package com.teachermovies.bridge.cli

import com.teachermovies.bridge.tv.ApiFailure

/**
 * The Spanish text a subcommand prints for an [ApiFailure] (#271), shared by `pair`, `doctor` and
 * `logs` so the three say the same thing about the same refusal.
 *
 * It carries no token, no PIN, no request header and no response body: an [ApiFailure.Network]
 * reason is a class name or a fixed phrase, and an [ApiFailure.Http] only repeats the status and the
 * TV's own `error`/`message` fields, which the TV guarantees are free of secrets (ADR-0002).
 */
internal fun describeFailure(failure: ApiFailure): String =
    when (failure) {
        ApiFailure.Unauthorized -> {
            "la TV ha rechazado el token (401); está revocado o la TV ha olvidado este portátil"
        }

        is ApiFailure.Http -> {
            httpText(failure)
        }

        is ApiFailure.Network -> {
            "no se ha podido hablar con la TV (${failure.reason})"
        }
    }

private fun httpText(failure: ApiFailure.Http): String {
    val code = failure.code?.let { " $it" } ?: ""
    val message = failure.message?.let { ": $it" } ?: ""
    return "la TV ha respondido ${failure.status}$code$message"
}
