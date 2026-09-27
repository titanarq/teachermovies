package com.teachermovies.core.db

import androidx.room.Entity

/**
 * One movie's automatic-subtitle search in one language (#274, ADR-0005 §5: "fetch state is kept in
 * Room"). [state] holds a `SubtitleFetchState` name, exactly like `torrents.state` holds a
 * `DownloadState` one: the table stays readable and a new state needs no schema change. Mapping to
 * and from `com.teachermovies.core.model.SubtitleFetch` is the repository's job, so an unknown
 * `state` string never reaches a caller.
 *
 * [nextRetryEpochMs] is what makes a not-found search wait seven days instead of being retried on
 * every pass; [movieHash] is the OpenSubtitles moviehash of the movie file (#279), duplicated across
 * the movie's language rows because it belongs to the file, not to a language.
 */
@Entity(
    tableName = "subtitle_fetch_state",
    primaryKeys = ["infoHash", "language"],
)
data class SubtitleFetchStateEntity(
    val infoHash: String,
    val language: String,
    val state: String,
    val movieHash: String?,
    val localPath: String?,
    val variantLabel: String?,
    val attempts: Int,
    val lastAttemptEpochMs: Long?,
    val nextRetryEpochMs: Long?,
    val errorMessage: String?,
    val updatedAtEpochMs: Long,
)
