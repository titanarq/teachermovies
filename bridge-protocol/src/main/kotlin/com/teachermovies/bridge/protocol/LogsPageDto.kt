package com.teachermovies.bridge.protocol

import kotlinx.serialization.Serializable

/**
 * Body of `GET /api/logs` (ADR-0006 §4): one page of the TV's ring buffer, oldest first.
 *
 * [bootId] identifies the boot of the TV process that stored [entries]; a reader that sees it
 * change discards its [LogEntryDto.seq] cursor and pages again from `since = 0`.
 */
@Serializable
data class LogsPageDto(
    val bootId: String,
    val entries: List<LogEntryDto>,
)
