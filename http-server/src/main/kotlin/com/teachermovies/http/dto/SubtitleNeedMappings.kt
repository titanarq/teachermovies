package com.teachermovies.http.dto

import com.teachermovies.bridge.protocol.SubtitleNeedDto
import com.teachermovies.core.model.SubtitleFetch
import com.teachermovies.core.model.SubtitleFetchState

/**
 * This fetch row as one element of `GET /api/bridge/subtitle-needs` (#280), for a movie called
 * [title]: the state in snake_case as every other enum goes on the wire (#57), everything else as
 * stored. Only a row a search may act on reaches this mapping, so its state is `pending`,
 * `not_found` or `failed` -- never `searching` or `downloaded`.
 */
internal fun SubtitleFetch.toNeedDto(title: String): SubtitleNeedDto =
    SubtitleNeedDto(
        torrentId = torrentId.value,
        title = title,
        language = language,
        movieHash = movieHash,
        state = state.toWireValue(),
        attempts = attempts,
    )

internal fun SubtitleFetchState.toWireValue(): String =
    when (this) {
        SubtitleFetchState.Pending -> "pending"
        SubtitleFetchState.Searching -> "searching"
        SubtitleFetchState.Downloaded -> "downloaded"
        SubtitleFetchState.NotFound -> "not_found"
        SubtitleFetchState.Failed -> "failed"
    }
