package com.teachermovies.bridge.protocol

import kotlinx.serialization.Serializable

/**
 * `data` of the first frame of `GET /api/logs/stream` (`event: boot`), sent before any backlog
 * replay: [bootId] is the boot of the TV process the stream belongs to, and a change from the
 * one a mirror last saw means [LogEntryDto.seq] started over (ADR-0006 §5).
 */
@Serializable
data class LogStreamBootDto(
    val bootId: String,
)
