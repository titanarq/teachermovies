package com.teachermovies.assistant.subtitles

/** Parses one subtitle file format into a [SubtitleTrack]. */
interface SubtitleParser {
    /** Whether this parser handles a file named [fileName], judged by its extension. */
    fun supports(fileName: String): Boolean

    /** Parses already-decoded subtitle [text] into a [SubtitleTrack]. */
    fun parse(text: String): SubtitleTrack
}
