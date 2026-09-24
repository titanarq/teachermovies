package com.teachermovies.player.api

/** The standalone text format [Player.extractTextSubtitle] wrote an embedded track as. */
enum class SubtitleFormat {
    /** SubRip: numbered cues with `HH:MM:SS,mmm --> HH:MM:SS,mmm` timing. */
    SRT,

    /** Advanced SubStation Alpha: the track's own header plus timed `Dialogue:` lines. */
    ASS,
}
