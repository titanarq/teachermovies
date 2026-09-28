package com.teachermovies.http

import com.teachermovies.core.model.SubtitleFetch
import com.teachermovies.core.model.SubtitleFetchState
import com.teachermovies.core.model.TorrentId
import com.teachermovies.http.auth.TokenScope
import com.teachermovies.http.auth.requireBearer
import com.teachermovies.http.dto.toSearchDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.first

/**
 * The phone's per-movie "Buscar subtítulos" action (#285, ADR-0005 §5), phone tokens only:
 *
 * - `POST /api/library/{id}/subtitles/search` makes every language the movie still lacks due right
 *   now -- a not-found row stops waiting out its seven days, a failed one is pending again -- and
 *   sends the bridge its `subtitles-needed` nudge for that movie. A downloaded row is left alone:
 *   there is nothing to retry. A row marked `searching` is left alone too while a bridge is
 *   connected (that search is in flight); with no bridge connected it is a search that died with
 *   its laptop, so it becomes pending and the bridge picks it up when it reconnects.
 * - `GET /api/library/{id}/subtitles` reports the same state without changing anything, so the web
 *   UI can follow a search to its end.
 *
 * Both answer a [com.teachermovies.http.dto.SubtitleSearchDto]; a movie not in the library is 404
 * `unknown_torrent`. The manual upload of `POST /api/subtitles` is a separate path, unchanged.
 */
internal fun Route.subtitleSearchRoutes(deps: ServerDeps) {
    requireBearer(deps.pairing, setOf(TokenScope.PHONE)) {
        get("/api/library/{id}/subtitles") {
            val id = call.libraryMovieOrRespond(deps) ?: return@get
            call.respond(deps.searchState(id))
        }
        post("/api/library/{id}/subtitles/search") {
            val id = call.libraryMovieOrRespond(deps) ?: return@post
            val now = deps.clock()
            val bridgeConnected = deps.bridge.connected.value
            deps
                .fetchRows(id)
                .filter { it.shouldRetryNow(bridgeConnected) }
                .forEach { deps.subtitleFetches.save(it.retryNow(now)) }
            deps.bridge.notifySubtitlesNeeded(id)
            call.respond(deps.searchState(id))
        }
    }
}

/** The `{id}` path parameter as a movie in the library, or a 400/404 already sent and null. */
private suspend fun ApplicationCall.libraryMovieOrRespond(deps: ServerDeps): TorrentId? {
    val id =
        try {
            TorrentId(parameters["id"].orEmpty())
        } catch (_: IllegalArgumentException) {
            respondApiError(HttpStatusCode.BadRequest, ApiError("invalid_id", "Not a valid torrent id"))
            return null
        }
    if (deps.library.getLibraryItem(id) == null) {
        respondUnknownTorrent()
        return null
    }
    return id
}

private suspend fun ServerDeps.fetchRows(id: TorrentId): List<SubtitleFetch> =
    subtitleFetches.observeAll().first().filter { it.torrentId == id }

private suspend fun ServerDeps.searchState(id: TorrentId) = fetchRows(id).toSearchDto(id.value, bridge.connected.value)

private fun SubtitleFetch.shouldRetryNow(bridgeConnected: Boolean): Boolean =
    when (state) {
        SubtitleFetchState.Downloaded, SubtitleFetchState.Pending -> false
        SubtitleFetchState.Searching -> !bridgeConnected
        SubtitleFetchState.NotFound, SubtitleFetchState.Failed -> true
    }

/** This row as a search due immediately; its attempts and moviehash are kept. */
private fun SubtitleFetch.retryNow(now: Long): SubtitleFetch =
    copy(
        state = SubtitleFetchState.Pending,
        nextRetryEpochMs = null,
        errorMessage = null,
        updatedAtEpochMs = now,
    )
