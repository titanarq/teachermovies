package com.teachermovies.core.model

/**
 * The automatic-subtitle search state of one movie in one language (#274, ADR-0005 §5), persisted
 * in `subtitle_fetch_state` and keyed by ([torrentId], [language]).
 *
 * [language] is the two-letter tag the downloaded file carries (`en`, `es`); [variantLabel] is
 * `"latino"` when a Latin-American Spanish file was accepted as the last resort and null for the
 * preferred Castilian one, so the UI can label what it is showing. [movieHash] is the OpenSubtitles
 * moviehash of the movie file (#279), the same for every language row of one movie.
 */
data class SubtitleFetch(
    val torrentId: TorrentId,
    val language: String,
    val state: SubtitleFetchState,
    val movieHash: String?,
    val localPath: String?,
    val variantLabel: String?,
    val attempts: Int,
    val lastAttemptEpochMs: Long?,
    val nextRetryEpochMs: Long?,
    val errorMessage: String?,
    val updatedAtEpochMs: Long,
) {
    /**
     * Whether a fetch loop may act on this row at [now]: a file already on disk never is, a search in
     * flight is not (the newest bridge stream owns it, ADR-0005 §2), and a not-found row waits out
     * [NOT_FOUND_RETRY_MS] before being searched again.
     */
    fun isDue(now: Long): Boolean =
        when (state) {
            SubtitleFetchState.Downloaded, SubtitleFetchState.Searching -> false
            SubtitleFetchState.NotFound -> nextRetryEpochMs == null || nextRetryEpochMs <= now
            SubtitleFetchState.Pending, SubtitleFetchState.Failed -> true
        }

    /** This row as a fresh search, one attempt more, due again immediately. */
    fun searchingAt(now: Long): SubtitleFetch =
        copy(
            state = SubtitleFetchState.Searching,
            attempts = attempts + 1,
            lastAttemptEpochMs = now,
            nextRetryEpochMs = null,
            errorMessage = null,
            updatedAtEpochMs = now,
        )

    /** This row with a downloaded file on disk; the search is over and never due again. */
    fun downloadedAt(
        now: Long,
        path: String,
        variant: String? = null,
    ): SubtitleFetch =
        copy(
            state = SubtitleFetchState.Downloaded,
            localPath = path,
            variantLabel = variant,
            nextRetryEpochMs = null,
            errorMessage = null,
            updatedAtEpochMs = now,
        )

    /** This row after an empty search, retried only once [NOT_FOUND_RETRY_MS] has passed. */
    fun notFoundAt(now: Long): SubtitleFetch =
        copy(
            state = SubtitleFetchState.NotFound,
            nextRetryEpochMs = now + NOT_FOUND_RETRY_MS,
            errorMessage = null,
            updatedAtEpochMs = now,
        )

    /** This row after a search that errored, due again on the next pass. */
    fun failedAt(
        now: Long,
        reason: String? = null,
    ): SubtitleFetch =
        copy(
            state = SubtitleFetchState.Failed,
            nextRetryEpochMs = null,
            errorMessage = reason,
            updatedAtEpochMs = now,
        )

    companion object {
        /** ADR-0005 §5: a not-found search is retried after seven days, not on every pass. */
        const val NOT_FOUND_RETRY_MS: Long = 7L * 24 * 60 * 60 * 1000

        /** The row a movie starts with when it is first seen to need subtitles in [language]. */
        fun pending(
            torrentId: TorrentId,
            language: String,
            movieHash: String? = null,
            now: Long = 0L,
        ): SubtitleFetch =
            SubtitleFetch(
                torrentId = torrentId,
                language = language,
                state = SubtitleFetchState.Pending,
                movieHash = movieHash,
                localPath = null,
                variantLabel = null,
                attempts = 0,
                lastAttemptEpochMs = null,
                nextRetryEpochMs = null,
                errorMessage = null,
                updatedAtEpochMs = now,
            )
    }
}
