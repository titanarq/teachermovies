package com.teachermovies.core.model

/** The player's position in the file it is playing; [durationMs] is 0 until the media is parsed. */
data class PlaybackState(
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
)
