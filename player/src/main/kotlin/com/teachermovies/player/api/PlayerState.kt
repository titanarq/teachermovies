package com.teachermovies.player.api

/**
 * Lifecycle of the media a [Player] has loaded.
 *
 * Sealed so a consumer switches over it exhaustively, without an `else` branch to keep in sync
 * when a state is added.
 */
sealed interface PlayerState {
    /** Nothing loaded, or the player has been released. */
    data object Idle : PlayerState

    /** [Player.open] was called and the media is still being loaded and parsed. */
    data object Opening : PlayerState

    data object Playing : PlayerState

    data object Paused : PlayerState

    /** Playback ran to the end of the media on its own. */
    data object Ended : PlayerState

    /** Playback cannot continue; [message] is what the implementation reports to the user. */
    data class Error(
        val message: String,
    ) : PlayerState
}
