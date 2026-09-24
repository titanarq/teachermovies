package com.teachermovies.player.session

import com.teachermovies.core.model.LibraryItem

/** Outcome of [PlaybackSession.open]; sealed so the screen handles every case exhaustively. */
sealed interface SessionResult {
    /** [item] is loaded into the player, with its external subtitles added. */
    data class Opened(val item: LibraryItem) : SessionResult

    /** No completed library item with that id: never downloaded, still downloading, or deleted. */
    data object NotFound : SessionResult

    /** The item is in the library but its media file is gone from [path] (e.g. the USB disk is out). */
    data class FileMissing(val path: String) : SessionResult
}
