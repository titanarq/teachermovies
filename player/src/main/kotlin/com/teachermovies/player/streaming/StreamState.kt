package com.teachermovies.player.streaming

/** What a [StreamingPlaybackController] is doing with the still-downloading file it supervises. */
sealed interface StreamState {
    /** Nothing supervised: never started, stopped, or the media ended. */
    data object Idle : StreamState

    /**
     * Waiting for the head, tail and start buffer before opening the file: [readyBytes] of the
     * [requiredBytes] they add up to are on disk.
     */
    data class Preparing(
        val readyBytes: Long,
        val requiredBytes: Long,
    ) : StreamState

    /** The file is open and enough bytes are ready ahead of the playback position. */
    data object Streaming : StreamState

    /**
     * The controller paused playback because only [readyBytes] are ready ahead of the position;
     * it plays again on its own once [resumeAtBytes] are.
     */
    data class Buffering(
        val readyBytes: Long,
        val resumeAtBytes: Long,
    ) : StreamState

    /** Supervision ended because the engine or the player failed; [reason] is their own text. */
    data class Failed(
        val reason: String,
    ) : StreamState
}

/** How [StreamingPlaybackController.start] ended. */
sealed interface StreamResult {
    /** Every open range was ready; the file is open, playing and supervised. */
    data object Opened : StreamResult

    /** The engine does not know the torrent. */
    data object UnknownTorrent : StreamResult

    /** The engine cannot answer range queries; the caller decides whether to play unsupervised. */
    data object Unsupported : StreamResult

    /** The engine failed with an I/O error; [reason] is its own text. */
    data class Failed(
        val reason: String,
    ) : StreamResult
}
