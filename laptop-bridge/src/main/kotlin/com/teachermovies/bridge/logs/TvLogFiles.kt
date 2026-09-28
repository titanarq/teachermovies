package com.teachermovies.bridge.logs

import com.teachermovies.bridge.config.ConfigLocation
import com.teachermovies.bridge.protocol.LogEntryDto
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * The laptop's copy of the TV log (#272, ADR-0006 §5): one `<yyyy-mm-dd>.log` per day in [dir],
 * each line `<ISO> <L> <module> <msg>` -- the entry's own time as an ISO-8601 local date-time with
 * millis and offset, the level's initial (`D`, `I`, `W`, `E`), the module and the message.
 *
 * An entry goes to the file of the day its own `timeMs` falls on in [zone], so the files roll
 * daily by the TV's clock rather than by when the bridge happened to receive a backfilled line. A
 * gap marker ([gap]) goes to the file of [clock]'s day, since it records something the bridge saw
 * now. A message's line breaks are written as a literal `\n`, so one entry is always one line.
 *
 * The directory is created `0700` and every file `0600`, like the bridge's own log: the TV
 * redacted its lines (ADR-0006 §3), but they are still a record of what was watched. Files whose
 * date is more than [keepDays] days before today are deleted each time a new day's file is
 * started. Nothing here catches [IOException]: the mirror decides what a failed write means.
 */
class TvLogFiles(
    val dir: Path,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Instant = Instant::now,
    private val keepDays: Long = KEEP_DAYS,
) {
    private var lastDay: LocalDate? = null

    /** Appends [entry] to its day's file. */
    fun append(entry: LogEntryDto) {
        val time = Instant.ofEpochMilli(entry.timeMs)
        write(time.atZone(zone).toLocalDate(), line(time, levelLetter(entry.level), entry.module, entry.message))
    }

    /** Appends the visible marker of a TV restart: from here on the lines belong to [newBootId]. */
    fun gap(
        oldBootId: String,
        newBootId: String,
    ) {
        val now = clock()
        val message =
            "===== la TV ha vuelto a arrancar (arranque ${oldBootId.take(BOOT_ID_LENGTH)} -> " +
                "${newBootId.take(BOOT_ID_LENGTH)}): lo que no se copió antes del reinicio se ha perdido ====="
        write(now.atZone(zone).toLocalDate(), line(now, GAP_LETTER, BRIDGE_MODULE, message))
    }

    /** The file [day]'s lines go to. */
    fun fileFor(day: LocalDate): Path = dir.resolve("${FILE_DATE.format(day)}$SUFFIX")

    private fun write(
        day: LocalDate,
        text: String,
    ) {
        val file = fileFor(day)
        createPrivate(file)
        Files.write(file, (text + "\n").toByteArray(StandardCharsets.UTF_8), StandardOpenOption.APPEND)
        if (lastDay != day) {
            lastDay = day
            prune()
        }
    }

    /** Deletes the dated files older than [keepDays] days before today; other files are left alone. */
    private fun prune() {
        val oldestKept = clock().atZone(zone).toLocalDate().minusDays(keepDays)
        Files.newDirectoryStream(dir, "*$SUFFIX").use { files ->
            for (file in files) {
                val day = dayOf(file) ?: continue
                if (day < oldestKept) Files.deleteIfExists(file)
            }
        }
    }

    private fun createPrivate(file: Path) {
        if (Files.exists(file)) return
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(
                dir,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            )
        }
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } catch (_: FileAlreadyExistsException) {
            // Created between the check and here; appending to it is all that is needed.
        }
    }

    private fun line(
        time: Instant,
        letter: Char,
        module: String,
        message: String,
    ): String {
        val oneLine = message.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\\n")
        val moduleWord = module.ifBlank { "-" }.replace(Regex("\\s"), "_")
        return "${LINE_TIME.format(time.atZone(zone))} $letter $moduleWord $oneLine"
    }

    companion object {
        /** Days of files kept besides today's (ADR-0006 §5). */
        const val KEEP_DAYS = 14L

        /** Where the mirror writes when nothing else is configured, relative to the working directory. */
        const val DEFAULT_DIR = ".cache/tv-logs"

        /** Moves the mirror elsewhere when set and non-blank; a leading `~` is the home directory. */
        const val DIR_VARIABLE = "TEACHERMOVIES_TV_LOGS_DIR"

        /** `$TEACHERMOVIES_TV_LOGS_DIR`, otherwise [DEFAULT_DIR] against the working directory; always absolute. */
        fun defaultDir(
            home: Path,
            env: (String) -> String?,
        ): Path = ConfigLocation.expand(env(DIR_VARIABLE)?.takeIf { it.isNotBlank() } ?: DEFAULT_DIR, home)

        /** The level column of a gap marker: not a TV level, so a filter on `E`/`W` never hides it. */
        const val GAP_LETTER = '-'

        private const val BRIDGE_MODULE = "bridge"
        private const val SUFFIX = ".log"
        private const val BOOT_ID_LENGTH = 8

        private val FILE_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        private val LINE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

        /** `debug` -> `D` and so on; a level this bridge does not know keeps its own initial. */
        fun levelLetter(level: String): Char = level.firstOrNull()?.uppercaseChar() ?: '?'

        private fun dayOf(file: Path): LocalDate? =
            try {
                LocalDate.parse(file.fileName.toString().removeSuffix(SUFFIX), FILE_DATE)
            } catch (_: DateTimeParseException) {
                null
            }
    }
}
