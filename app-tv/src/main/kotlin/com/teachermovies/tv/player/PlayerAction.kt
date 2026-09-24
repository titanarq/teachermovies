package com.teachermovies.tv.player

/**
 * What a remote-control key asks the player screen to do (#78). Sealed so the ViewModel handles
 * every action exhaustively; [RemoteKeyMapper] is the only place key codes become actions.
 */
sealed interface PlayerAction {
    data object TogglePlayPause : PlayerAction

    data object Play : PlayerAction

    data object Pause : PlayerAction

    /** Seek [deltaMs] relative to the current position (negative = back). */
    data class SeekBy(val deltaMs: Long) : PlayerAction

    /** Open the audio/subtitle track panel (#79). */
    data object ShowTracks : PlayerAction

    /** Close the session (position saved) and go back to Biblioteca. */
    data object Exit : PlayerAction
}
