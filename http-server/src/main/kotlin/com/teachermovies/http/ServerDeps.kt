package com.teachermovies.http

import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.TorrentRepository
import com.teachermovies.http.auth.PairingManager
import com.teachermovies.storage.SpaceInfo
import com.teachermovies.torrent.api.EngineResult
import com.teachermovies.torrent.api.TorrentEngine

/**
 * Everything the HTTP API reads from the rest of the app, injected by `AppContainer` (ADR-0003).
 *
 * Later issues extend this with repositories (#57, #60).
 *
 * @property remove removes a torrent (deleting its files too when the flag is `true`) and, only once
 * the engine confirms, its repository row (#219). `DELETE /api/torrents/{id}` calls this -- never
 * [TorrentEngine.remove] directly -- so a movie deleted from the phone also leaves Biblioteca and
 * `GET /api/library`. `AppContainer` wires it to `EngineRepositorySync.remove`, the same path the
 * TV's Descargas screen uses.
 * @property space free/total bytes of the current download volume, or `null` when unknown.
 * @property clock current time in epoch milliseconds (injectable for tests).
 * @property pairing PIN pairing and token validation behind `POST /api/pair` and `requireBearer`.
 * @property subtitles where `POST /api/subtitles` (#61) writes an uploaded subtitle file.
 * @property library persisted torrents; `GET /api/library` (#73) lists its completed movies.
 * @property allowTestRemoteHeader test-only (#59): when `true`, the `X-Test-Remote` header
 * overrides the socket's remote address for the LAN-address guard. Must stay `false` in
 * production; `AppContainer` never sets it.
 */
data class ServerDeps(
    val engine: TorrentEngine,
    val remove: suspend (id: TorrentId, deleteFiles: Boolean) -> EngineResult<Unit>,
    val space: () -> SpaceInfo?,
    val appVersion: String,
    val clock: () -> Long,
    val pairing: PairingManager,
    val subtitles: SubtitleStore,
    val library: TorrentRepository,
    val allowTestRemoteHeader: Boolean = false,
)
