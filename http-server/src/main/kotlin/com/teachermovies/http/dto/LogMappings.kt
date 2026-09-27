package com.teachermovies.http.dto

import com.teachermovies.bridge.protocol.LogEntryDto
import com.teachermovies.core.log.LogEntry

/** Maps a ring-buffer line to its wire shape (#269): every field as-is, the level in lower case. */
fun LogEntry.toDto(): LogEntryDto =
    LogEntryDto(
        seq = seq,
        timeMs = timeMs,
        level = level.name.lowercase(),
        module = module,
        message = message,
    )
