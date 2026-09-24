package com.teachermovies.core.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One torrent's persisted metadata. Only metadata lives here -- the media itself stays on disk
 * under `<volume>/Movies/<torrent-id>/` (AGENTS.md "Persistence").
 *
 * [state] holds a `DownloadState` name; mapping to and from the domain type is the repository's job
 * (#67), so the table stays readable and a new enum constant needs no schema change.
 */
@Entity(tableName = "torrents")
data class TorrentEntity(
    @PrimaryKey val infoHash: String,
    val name: String,
    val state: String,
    val progressPercent: Double,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val savePath: String?,
    val mainFileIndex: Int?,
    val mainFilePath: String?,
    val audioTrackId: String?,
    val subtitleTrackId: String?,
    val lastPositionMs: Long = 0,
    val addedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
    val errorMessage: String?,
)
