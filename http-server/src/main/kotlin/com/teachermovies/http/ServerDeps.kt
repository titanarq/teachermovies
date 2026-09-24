package com.teachermovies.http

import com.teachermovies.storage.SpaceInfo
import com.teachermovies.torrent.api.TorrentEngine

/**
 * Everything the HTTP API reads from the rest of the app, injected by `AppContainer` (ADR-0003).
 *
 * Later issues extend this with the pairing/token store (#58) and repositories (#57, #60).
 *
 * @property space free/total bytes of the current download volume, or `null` when unknown.
 * @property clock current time in epoch milliseconds (injectable for tests).
 */
data class ServerDeps(
    val engine: TorrentEngine,
    val space: () -> SpaceInfo?,
    val appVersion: String,
    val clock: () -> Long,
)
