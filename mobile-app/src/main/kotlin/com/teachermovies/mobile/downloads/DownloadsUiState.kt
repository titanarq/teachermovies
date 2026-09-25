package com.teachermovies.mobile.downloads

import com.teachermovies.mobile.api.TorrentSummary

/**
 * What the downloads screen shows (#198); produced only by [DownloadsViewModel]. The row texts are
 * not here: the screen builds each one with [DownloadFormat]. [notice] is the Spanish message of the
 * last magnet the user sent, shown once and dropped by [DownloadsViewModel.noticeShown].
 */
sealed interface DownloadsUiState {
    /** The message of the last [DownloadsViewModel.send], or `null` while there is none to show. */
    val notice: String?

    /** The TV has not answered a poll yet: it is being polled, or none is stored. */
    data class Loading(
        override val notice: String? = null,
    ) : DownloadsUiState

    /**
     * [items] is what the TV last answered, kept across failed polls so the list does not blink
     * away; [offline] is true while the TV is not answering (`TV sin conexión`).
     */
    data class Loaded(
        val items: List<TorrentSummary>,
        val offline: Boolean = false,
        override val notice: String? = null,
    ) : DownloadsUiState
}
