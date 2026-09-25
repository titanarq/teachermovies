package com.teachermovies.mobile.api

import kotlinx.serialization.Serializable

/**
 * `GET /api/status` body, mirrored from the TV's `StatusResponse` (docs/modules/http-server.md).
 * [engine] is the engine status in lower case; [freeBytes]/[totalBytes] are `null` when unknown.
 */
@Serializable
data class TvStatus(
    val version: String,
    val engine: String,
    val freeBytes: Long? = null,
    val totalBytes: Long? = null,
    val torrents: Int,
)
