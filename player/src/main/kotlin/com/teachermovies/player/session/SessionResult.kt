package com.teachermovies.player.session

import com.teachermovies.core.model.LibraryItem
import com.teachermovies.player.streaming.StreamResult

/** Outcome of [PlaybackSession.open] and [PlaybackSession.openStreaming]; sealed so the screen handles every case exhaustively. */
sealed interface SessionResult {
    /** [item] is loaded into the player, with its external subtitles added. */
    data class Opened(val item: LibraryItem) : SessionResult

    /** No completed library item with that id: never downloaded, still downloading, or deleted. */
    data object NotFound : SessionResult

    /**
     * The item is in the library but its media file is gone from [path] (e.g. the USB disk is out),
     * or -- for [PlaybackSession.openStreaming] -- the torrent has no main file selected yet, when
     * [path] is its save folder (empty if even that is unknown), or the engine does not know the
     * main file's size yet (metadata still arriving), when [path] is the main file's path.
     */
    data class FileMissing(val path: String) : SessionResult

    /**
     * [PlaybackSession.openStreaming] could not supervise the in-progress download: [failure] is
     * what [com.teachermovies.player.streaming.StreamingPlaybackController.start] answered, or
     * what its `fileSizeBytes` lookup failed with --
     * [StreamResult.UnknownTorrent], [StreamResult.Unsupported] or a [StreamResult.Failed] -- and the
     * player was never opened.
     */
    data class StreamingFailed(val failure: StreamResult) : SessionResult
}
