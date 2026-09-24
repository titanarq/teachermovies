package com.teachermovies.assistant.subtitles

import java.io.File

/** Outcome of parsing a subtitle file with [SubtitleParsers.parse]. */
sealed interface ParseResult {
    /** [track] was parsed successfully and has at least one cue. */
    data class Parsed(
        val track: SubtitleTrack,
    ) : ParseResult

    /** No registered [SubtitleParser] handles a file with this [extension]. */
    data class Unsupported(
        val extension: String,
    ) : ParseResult

    /** The file's extension is supported but its content could not be read as cues. */
    data class Malformed(
        val reason: String,
    ) : ParseResult
}

/**
 * Entry point for turning a subtitle file into a [SubtitleTrack]. Dispatches to the registered
 * [SubtitleParser] that [SubtitleParser.supports] the file name; no exception ever escapes
 * [parse] -- an unreadable, empty or unparseable file becomes [ParseResult.Malformed].
 */
object SubtitleParsers {
    private val parsers: List<SubtitleParser> = listOf(SrtParser(), AssParser())

    fun parse(file: File): ParseResult {
        val parser =
            parsers.firstOrNull { it.supports(file.name) }
                ?: return ParseResult.Unsupported(file.extension)

        return try {
            val text = SubtitleCharset.decode(file.readBytes())
            val track = parser.parse(text)
            if (track.cues.isEmpty()) {
                ParseResult.Malformed("no cues found in ${file.name}")
            } else {
                ParseResult.Parsed(track)
            }
        } catch (e: Exception) {
            ParseResult.Malformed(e.message ?: e::class.java.simpleName)
        }
    }
}
