package com.teachermovies.player.mkv

import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Turns the decoded blocks of one Matroska subtitle track into the text of a standalone file, per
 * the Matroska subtitle codec mappings: `S_TEXT/UTF8` blocks are the bare cue text, `S_TEXT/ASS`
 * and `S_TEXT/SSA` blocks are a `Dialogue:` line without its times and with a leading `ReadOrder`.
 */
internal object SubtitleWriter {
    /** A block as the file stores it: times in `TimecodeScale` ticks, the frame already decoded. */
    class RawCue(
        val ticks: Long,
        val durationTicks: Long?,
        val payload: ByteArray,
    )

    class TimedCue(
        val startMs: Long,
        val endMs: Long,
        val text: String,
    )

    /**
     * Cues sorted by start, in milliseconds. A block without `BlockDuration` ends where the next
     * later cue starts, or [DEFAULT_LAST_DURATION_MS] after its own start when none does.
     */
    fun timedCues(
        blocks: List<RawCue>,
        timecodeScale: Long,
    ): List<TimedCue> {
        val sorted = blocks.sortedBy { it.ticks }
        val starts = sorted.map { toMs(it.ticks, timecodeScale) }
        return sorted.mapIndexed { index, block ->
            val start = starts[index]
            val end =
                when (val duration = block.durationTicks) {
                    null -> {
                        (index + 1 until starts.size).firstOrNull { starts[it] > start }?.let(starts::get)
                            ?: (start + DEFAULT_LAST_DURATION_MS)
                    }

                    else -> {
                        toMs(block.ticks + duration, timecodeScale)
                    }
                }
            TimedCue(start, end, String(block.payload, Charsets.UTF_8).trimEnd('\u0000'))
        }
    }

    /** SubRip text, cues numbered from 1; null when no cue has any text. */
    fun srt(cues: List<TimedCue>): String? {
        val texts =
            cues.mapNotNull { cue ->
                val text = cue.text.normalizeNewlines().trim('\n')
                if (text.isBlank()) null else cue to text
            }
        if (texts.isEmpty()) return null
        return buildString {
            texts.forEachIndexed { index, (cue, text) ->
                append(index + 1).append('\n')
                append(srtTime(cue.startMs)).append(" --> ").append(srtTime(cue.endMs)).append('\n')
                append(text).append("\n\n")
            }
        }
    }

    /**
     * ASS text: [header] (the track's `CodecPrivate`) with an `[Events]` section and `Format:` line
     * added when it lacks them, then one `Dialogue:` per block in `ReadOrder` order. Null when no
     * block is a well-formed `ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text`.
     */
    fun ass(
        header: String,
        cues: List<TimedCue>,
    ): String? {
        val dialogues =
            cues
                .mapNotNull { cue ->
                    val fields =
                        cue.text
                            .normalizeNewlines()
                            .trimEnd('\n')
                            .split(',', limit = ASS_BLOCK_FIELDS)
                    val readOrder = fields.first().trim().toLongOrNull()
                    if (fields.size < ASS_BLOCK_FIELDS || readOrder == null) {
                        null
                    } else {
                        readOrder to dialogue(fields, cue)
                    }
                }.sortedBy { it.first }
                .map { it.second }
        if (dialogues.isEmpty()) return null
        return buildString {
            append(eventsHeader(header))
            dialogues.forEach { append(it).append('\n') }
        }
    }

    private fun dialogue(
        fields: List<String>,
        cue: TimedCue,
    ): String {
        // fields: ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        val rest = fields.subList(2, ASS_BLOCK_FIELDS).joinToString(",")
        // A Dialogue line is one line: a stray newline inside the text becomes ASS's own `\N`.
        return "Dialogue: ${fields[1]},${assTime(cue.startMs)},${assTime(cue.endMs)},$rest".replace("\n", "\\N")
    }

    private fun eventsHeader(header: String): String {
        val lines =
            header
                .removePrefix("")
                .trimEnd('\u0000')
                .normalizeNewlines()
                .trimEnd('\n')
                .lines()
                .toMutableList()
        if (lines.size == 1 && lines[0].isEmpty()) lines.clear()
        val events = lines.indexOfFirst { it.trim().equals(EVENTS_SECTION, ignoreCase = true) }
        if (events < 0) {
            if (lines.isNotEmpty()) lines += ""
            lines += EVENTS_SECTION
            lines += EVENTS_FORMAT
        } else {
            val sectionEnd =
                (events + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("[") } ?: lines.size
            val hasFormat =
                (events + 1 until sectionEnd).any {
                    lines[it].trim().startsWith(
                        "Format:",
                        ignoreCase = true,
                    )
                }
            if (!hasFormat) lines.add(events + 1, EVENTS_FORMAT)
        }
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun String.normalizeNewlines(): String = replace("\r\n", "\n").replace('\r', '\n')

    private fun toMs(
        ticks: Long,
        timecodeScale: Long,
    ): Long = maxOf(0L, ticks) * timecodeScale / NANOS_PER_MS

    /** `HH:MM:SS,mmm` */
    fun srtTime(ms: Long): String {
        val hours = ms / 3_600_000
        val minutes = ms / 60_000 % 60
        val seconds = ms / 1_000 % 60
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, ms % 1_000)
    }

    /** `H:MM:SS.cc` */
    fun assTime(ms: Long): String {
        val hours = ms / 3_600_000
        val minutes = ms / 60_000 % 60
        val seconds = ms / 1_000 % 60
        return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", hours, minutes, seconds, ms % 1_000 / 10)
    }

    /** Writes [text] next to [destination] and renames it into place, so a failure leaves no partial file. */
    fun writeAtomically(
        destination: File,
        text: String,
    ) {
        val target = destination.absoluteFile
        val directory = target.parentFile ?: throw IOException("destination has no directory")
        val temp = File.createTempFile(".${target.name}.", ".part", directory)
        try {
            temp.writeText(text, Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                // `renameTo` does not replace an existing file on every platform.
                target.delete()
                if (!temp.renameTo(target)) throw IOException("could not write destination")
            }
        } finally {
            temp.delete()
        }
    }

    const val DEFAULT_LAST_DURATION_MS = 5_000L
    private const val NANOS_PER_MS = 1_000_000L
    private const val ASS_BLOCK_FIELDS = 9
    private const val EVENTS_SECTION = "[Events]"
    private const val EVENTS_FORMAT = "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
}
