package com.teachermovies.mobile.api

/**
 * The phone app's client of the TV's HTTP API (#196, docs/modules/http-server.md). [baseUrl] (for
 * example `http://192.168.1.20:8787`, no trailing slash needed) and, for protected calls, the
 * bearer `token` are passed on every call, so one instance serves any TV. No method throws: every
 * outcome is a sealed result, and only coroutine cancellation propagates.
 */
interface TvApi {
    /** `GET /api/status` (public, no token). */
    suspend fun status(baseUrl: String): ApiResult<TvStatus>

    /** `POST /api/pair` `{"pin","deviceName"}` (public, no token). */
    suspend fun pair(
        baseUrl: String,
        pin: String,
        deviceName: String,
    ): PairOutcome

    /** `GET /api/torrents` with `Authorization: Bearer <token>`. */
    suspend fun torrents(
        baseUrl: String,
        token: String,
    ): ApiResult<List<TorrentSummary>>

    /** `POST /api/torrents/magnet` `{"magnet"}` with `Authorization: Bearer <token>`. */
    suspend fun addMagnet(
        baseUrl: String,
        token: String,
        magnet: String,
    ): AddMagnetOutcome
}
