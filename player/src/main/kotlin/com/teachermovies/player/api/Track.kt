package com.teachermovies.player.api

/**
 * One audio or subtitle track of the open media.
 *
 * [id] is what [Player.selectAudio] and [Player.selectSubtitle] take, and is only meaningful to
 * the implementation that produced it. [language] is the language the media declares for the
 * track, null when it declares none -- which is the common case for real-world MKVs and always the
 * case for an externally added subtitle file, whose language then comes from its file name.
 * [external] is true for a track made from a subtitle file added with `addExternalSubtitle` (a
 * sidecar or a download), false for a track the container carries -- the assistant must not take
 * the former for an embedded one.
 */
data class Track(
    val id: String,
    val name: String,
    val language: String?,
    val external: Boolean = false,
)
