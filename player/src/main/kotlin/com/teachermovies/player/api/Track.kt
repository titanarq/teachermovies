package com.teachermovies.player.api

/**
 * One audio or subtitle track of the open media.
 *
 * [id] is what [Player.selectAudio] and [Player.selectSubtitle] take, and is only meaningful to
 * the implementation that produced it. [language] is the language the media declares for the
 * track, null when it declares none -- which is the common case for real-world MKVs and always the
 * case for an externally added subtitle file.
 */
data class Track(
    val id: String,
    val name: String,
    val language: String?,
)
