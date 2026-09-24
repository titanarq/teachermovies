package com.teachermovies.core.model

/**
 * A finished download that the library offers to play.
 *
 * [mainFilePath] points at the media file on the volume, [lastPositionMs] is where playback stopped
 * so it can resume, and the track ids are the ones the viewer chose for that file.
 */
data class LibraryItem(
    val id: TorrentId,
    val title: String,
    val mainFilePath: String,
    val sizeBytes: Long,
    val lastPositionMs: Long,
    val audioTrackId: String?,
    val subtitleTrackId: String?,
    val completedAtEpochMs: Long,
)
