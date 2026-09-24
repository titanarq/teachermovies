package com.teachermovies.http.dto

import com.teachermovies.torrent.api.TorrentFileInfo
import kotlinx.serialization.Serializable

/**
 * One file inside a torrent over the wire (`GET /api/torrents/{id}/files`; docs/VISION.md
 * "API local"). [priority] is [com.teachermovies.torrent.api.FilePriority] in lower-case
 * (`skip`|`normal`|`high`).
 */
@Serializable
data class FileDto(
    val index: Int,
    val path: String,
    val size: Long,
    val priority: String,
    val downloadedBytes: Long,
)

/** Maps this engine file to its wire shape; see [FileDto]. */
fun TorrentFileInfo.toDto(): FileDto =
    FileDto(
        index = index,
        path = path,
        size = sizeBytes,
        priority = priority.name.lowercase(),
        downloadedBytes = downloadedBytes,
    )
