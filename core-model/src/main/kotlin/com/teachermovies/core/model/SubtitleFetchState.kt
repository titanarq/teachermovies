package com.teachermovies.core.model

/**
 * Where one movie's automatic-subtitle search stands (#274, ADR-0005 §5). The bridge owns the moves
 * (#282); this is the vocabulary the fetch-state row stores, as its `state` column's name.
 *
 * - [Pending]: the movie needs subtitles and no bridge has picked the search up yet.
 * - [Searching]: a bridge is looking on OpenSubtitles right now.
 * - [Downloaded]: a file is on disk under the movie's `subs/` directory ([SubtitleFetch.localPath]).
 * - [NotFound]: the search came up empty; retried only after [SubtitleFetch.NOT_FOUND_RETRY_MS].
 * - [Failed]: the search errored (laptop offline, quota, credentials); retried on the next pass.
 */
enum class SubtitleFetchState {
    Pending,
    Searching,
    Downloaded,
    NotFound,
    Failed,
}
