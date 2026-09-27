package com.teachermovies.bridge.protocol

import kotlinx.serialization.Serializable

/**
 * One TV log line on the wire (ADR-0006): an element of the `GET /api/logs` page and the `data`
 * of a `log` event of `GET /api/logs/stream`, served from the TV's redacting ring buffer.
 *
 * [seq] is the cursor within one boot: `since` filters on it and a reader stores the last one it
 * saw; when [LogsPageDto.bootId] changes, the numbering started over. [level] is the TV's log
 * level in lower case (`debug`, `info`, `warn`, `error`), [timeMs] is epoch milliseconds, and
 * [message] arrived already redacted and truncated. The golden-JSON tests pin every key:
 * renaming a property is a protocol change.
 */
@Serializable
data class LogEntryDto(
    val seq: Long,
    val timeMs: Long,
    val level: String,
    val module: String,
    val message: String,
)
