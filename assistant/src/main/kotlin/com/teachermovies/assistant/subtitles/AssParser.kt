package com.teachermovies.assistant.subtitles

/**
 * Parses Advanced SubStation Alpha (`.ass`, `.ssa`) subtitle text.
 *
 * Reads the `[Events]` section only. Its `Format:` line determines which comma-separated field
 * holds `Start`, `End` and `Text` -- these are never assumed to be at fixed positions, since
 * scripts commonly reorder or add fields. Only `Dialogue:` lines are turned into cues;
 * `Comment:` lines and every other section (`[Script Info]`, `[V4+ Styles]`, ...) are ignored.
 */
class AssParser : SubtitleParser {
    override fun supports(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "")
        return extension.equals("ass", ignoreCase = true) || extension.equals("ssa", ignoreCase = true)
    }

    override fun parse(text: String): SubtitleTrack {
        val normalized = text.removePrefix(BOM).replace("\r\n", "\n").replace("\r", "\n")

        var inEvents = false
        var columnCount = 0
        var startColumn = -1
        var endColumn = -1
        var textColumn = -1
        val rawCues = mutableListOf<RawCue>()

        for (rawLine in normalized.split("\n")) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("[") && line.endsWith("]")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue

            when {
                line.startsWith("Format:", ignoreCase = true) -> {
                    val columns = line.substringAfter(":").split(",").map { it.trim() }
                    columnCount = columns.size
                    startColumn = columns.indexOfFirst { it.equals("Start", ignoreCase = true) }
                    endColumn = columns.indexOfFirst { it.equals("End", ignoreCase = true) }
                    textColumn = columns.indexOfFirst { it.equals("Text", ignoreCase = true) }
                }

                line.startsWith("Dialogue:", ignoreCase = true) -> {
                    if (startColumn == -1 || endColumn == -1 || textColumn == -1) continue
                    val fields = line.substringAfter(":").split(",", limit = columnCount)
                    if (fields.size < columnCount) continue

                    val startMs = parseTime(fields[startColumn].trim()) ?: continue
                    val endMs = parseTime(fields[endColumn].trim()) ?: continue
                    val cueText = cleanText(fields[textColumn])
                    if (cueText.isEmpty()) continue

                    rawCues += RawCue(startMs, endMs, cueText)
                }

                // "Comment:" lines and anything else inside [Events] are ignored.
                else -> Unit
            }
        }
        return buildTrack(rawCues)
    }

    private fun parseTime(value: String): Long? {
        val groups = TIME_REGEX.find(value)?.groupValues ?: return null
        val hours = groups[1].toLong()
        val minutes = groups[2].toLong()
        val seconds = groups[3].toLong()
        val centiseconds = groups[4].toLong()
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + centiseconds * 10L
    }

    private fun cleanText(raw: String): String =
        raw.replace(OVERRIDE_BLOCK_REGEX, "")
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .replace("\\h", " ")
            .trim()

    private companion object {
        const val BOM = "﻿"
        val TIME_REGEX = Regex("""(\d+):(\d{2}):(\d{2})\.(\d{2})""")
        val OVERRIDE_BLOCK_REGEX = Regex("""\{[^}]*}""")
    }
}
