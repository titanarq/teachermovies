package com.teachermovies.assistant.subtitles

import java.io.File
import java.util.Locale

/** Writes a [SubtitleTrack] as a standalone `.srt`, so a player can load cues the assistant holds. */
object SrtWriter {
    /** [track] as SRT text: numbered blocks, `HH:MM:SS,mmm` times, a blank line after each. */
    fun format(track: SubtitleTrack): String =
        buildString {
            track.cues.forEachIndexed { position, cue ->
                append(position + 1).append('\n')
                append("${timestamp(cue.startMs)} --> ${timestamp(cue.endMs)}\n")
                // A blank line would end the block early.
                append(
                    cue.text
                        .lines()
                        .filter { it.isNotBlank() }
                        .joinToString("\n"),
                )
                append("\n\n")
            }
        }

    /** Writes [track] to [file] as UTF-8 SRT, creating its folder. */
    fun write(
        track: SubtitleTrack,
        file: File,
    ) {
        file.parentFile?.mkdirs()
        file.writeText(format(track), Charsets.UTF_8)
    }

    private fun timestamp(ms: Long): String {
        val total = maxOf(0L, ms)
        val hours = total / 3_600_000
        val minutes = total / 60_000 % 60
        val seconds = total / 1_000 % 60
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, total % 1_000)
    }
}
