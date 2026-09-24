package com.teachermovies.http

import com.teachermovies.torrent.api.EngineError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall

/** An HTTP status plus the [ApiError] body a route sends for it. */
data class HttpError(
    val status: HttpStatusCode,
    val body: ApiError,
)

/**
 * The single place an [EngineError] becomes an HTTP response (#60). Engine-provided text (e.g.
 * [EngineError.Io.message]) is never copied into the body: it may carry file paths or internals.
 */
fun EngineError.toHttp(): HttpError =
    when (this) {
        EngineError.InvalidMagnet -> {
            HttpError(HttpStatusCode.BadRequest, ApiError("invalid_magnet", "Not a valid magnet URI"))
        }

        EngineError.InvalidTorrentFile -> {
            HttpError(HttpStatusCode.BadRequest, ApiError("invalid_torrent", "Not a valid .torrent file"))
        }

        EngineError.UnknownTorrent -> {
            HttpError(HttpStatusCode.NotFound, ApiError("unknown_torrent", "No such torrent"))
        }

        is EngineError.AlreadyExists -> {
            HttpError(HttpStatusCode.Conflict, ApiError("already_exists", "Torrent already added", id = id.value))
        }

        EngineError.NotReady -> {
            HttpError(HttpStatusCode.Conflict, ApiError("not_ready", "Torrent metadata not available yet"))
        }

        EngineError.Unsupported -> {
            HttpError(HttpStatusCode.NotImplemented, ApiError("unsupported", "Not supported by the engine"))
        }

        is EngineError.Io -> {
            HttpError(HttpStatusCode.InternalServerError, ApiError("io_error", "Storage or engine I/O failure"))
        }
    }

/** Responds with [error] mapped by [toHttp]. */
internal suspend fun ApplicationCall.respondEngineError(error: EngineError) {
    val http = error.toHttp()
    respondApiError(http.status, http.body)
}
