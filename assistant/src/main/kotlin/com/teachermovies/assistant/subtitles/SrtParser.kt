package com.teachermovies.assistant.subtitles

/**
 * Parses SubRip (`.srt`) subtitle text.
 *
 * Tolerates the common real-world shape: a leading BOM, CRLF or LF line endings, either `,` or
 * `.` as the millisecond separator, multi-line cue text, extra blank lines between blocks, a
 * missing final blank line, and non-numeric or repeated block indexes (this parser never reads
 * the index number -- every cue is renumbered by [buildTrack]).
 */
class SrtParser : SubtitleParser {
    override fun supports(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").equals("srt", ignoreCase = true)

    override fun parse(text: String): SubtitleTrack {
        val normalized = text.removePrefix(BOM).replace("\r\n", "\n").replace("\r", "\n")
        val blocks = normalized.split(BLANK_LINES_REGEX)

        val rawCues = mutableListOf<RawCue>()
        for (block in blocks) {
            val lines = block.split("\n")
            val timeLineIndex = lines.indexOfFirst { TIME_REGEX.containsMatchIn(it) }
            if (timeLineIndex == -1) continue
            val groups = TIME_REGEX.find(lines[timeLineIndex])?.groupValues ?: continue

            val cueText = lines.drop(timeLineIndex + 1)
                .joinToString("\n") { it.replace(TAG_REGEX, "") }
                .trim()
            if (cueText.isEmpty()) continue

            rawCues += RawCue(
                startMs = toMillis(groups[1], groups[2], groups[3], groups[4]),
                endMs = toMillis(groups[5], groups[6], groups[7], groups[8]),
                text = cueText,
            )
        }
        return buildTrack(rawCues)
    }

    private fun toMillis(hours: String, minutes: String, seconds: String, millis: String): Long =
        hours.toLong() * 3_600_000L +
            minutes.toLong() * 60_000L +
            seconds.toLong() * 1_000L +
            millis.toLong()

    private companion object {
        const val BOM = "﻿"
        val BLANK_LINES_REGEX = Regex("\n{2,}")

        // Groups: 1-4 = start H/M/S/mmm, 5-8 = end H/M/S/mmm.
        val TIME_REGEX = Regex(
            """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})""",
        )
        val TAG_REGEX = Regex("""(?i)</?(?:i|b|u)>|<font\b[^>]*>|</font\s*>""")
    }
}
