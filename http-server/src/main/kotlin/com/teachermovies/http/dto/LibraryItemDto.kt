package com.teachermovies.http.dto

import com.teachermovies.core.model.LibraryItem
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * One movie ready to watch over the wire (`GET /api/library`, #73). [completedAt] is ISO-8601 UTC
 * (`2026-09-24T10:15:30Z`). Deliberately carries no file system path: the main file's location on
 * the TV's volume never leaves the device.
 */
@Serializable
data class LibraryItemDto(
    val id: String,
    val title: String,
    val sizeBytes: Long,
    val completedAt: String,
    val lastPositionMs: Long,
)

/** Maps this library item to its wire shape; see [LibraryItemDto]. */
fun LibraryItem.toDto(): LibraryItemDto =
    LibraryItemDto(
        id = id.value,
        title = title,
        sizeBytes = sizeBytes,
        completedAt = Instant.ofEpochMilli(completedAtEpochMs).toString(),
        lastPositionMs = lastPositionMs,
    )
