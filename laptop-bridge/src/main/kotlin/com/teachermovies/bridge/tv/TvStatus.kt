package com.teachermovies.bridge.tv

import kotlinx.serialization.Serializable

/**
 * `GET /api/status` body, mirrored from the TV's `StatusResponse` (docs/modules/http-server.md):
 * `:http-server` is an Android library, so the laptop program cannot depend on it and spells the
 * contract out itself, exactly as `:mobile-app` does. [engine] is the engine status in lower case;
 * [freeBytes] and [totalBytes] are null when the TV does not know them.
 *
 * `doctor` uses it as the cheapest "is that really a teachermovies TV, and is it up?" probe: the
 * route is public (ADR-0002), so it answers even with a token the TV has forgotten.
 */
@Serializable
data class TvStatus(
    val version: String,
    val engine: String,
    val freeBytes: Long? = null,
    val totalBytes: Long? = null,
    val torrents: Int,
)
