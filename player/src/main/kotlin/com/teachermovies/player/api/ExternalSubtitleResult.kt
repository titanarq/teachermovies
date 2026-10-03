package com.teachermovies.player.api

/**
 * Outcome of [Player.addExternalSubtitleTrack]. The call never throws: every failure is one of
 * these members, so a caller outside `:player` can branch on it without knowing about libVLC.
 */
sealed interface ExternalSubtitleResult {
    /** The file became [track] of the open media; it is not selected. */
    data class Added(
        val track: Track,
    ) : ExternalSubtitleResult

    /** The subtitle was not added: no open media, or the player refused the file. */
    data class NotAdded(
        val reason: String,
    ) : ExternalSubtitleResult

    /** The player accepted the file but never published the track it created within the timeout. */
    data object TimedOut : ExternalSubtitleResult
}
