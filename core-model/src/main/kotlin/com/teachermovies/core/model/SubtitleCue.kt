package com.teachermovies.core.model

/** One subtitle entry with the interval it is on screen for; [text] is the raw cue, markup kept. */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)
