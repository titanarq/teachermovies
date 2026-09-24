package com.teachermovies.torrent.api

/**
 * The outcome of a [TorrentEngine] call: [Ok] with the value, or [Failure] with the [EngineError]
 * that explains why not. Every fallible [TorrentEngine] method returns this instead of throwing, so
 * a caller pattern-matches the outcome instead of wrapping every call in a try/catch.
 */
sealed interface EngineResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : EngineResult<T>

    data class Failure(
        val error: EngineError,
    ) : EngineResult<Nothing>
}
