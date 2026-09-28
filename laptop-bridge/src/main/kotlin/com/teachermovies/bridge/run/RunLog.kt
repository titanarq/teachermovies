package com.teachermovies.bridge.run

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The bridge's own log of a `run` (#277): one `yyyy-MM-dd HH:mm:ss.SSS mensaje` line per event --
 * connected, dropped, backing off, re-discovered, each job and its outcome -- written to [out]
 * (the systemd journal once #278 runs it as a service) and appended to [file], created `0600`.
 *
 * Lines name a job by its kind, a prefix of its id and how it ended, never its text, and never a
 * token. When [file] cannot be written the log says so once on [out] and carries on without it: a
 * full disk must not stop the bridge from answering the TV.
 */
class RunLog(
    private val out: Appendable,
    private val file: Path?,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private var fileBroken = false

    @Synchronized
    fun line(message: String) {
        val text = "${TIME_FORMAT.format(clock().atZone(zone))} $message"
        out.appendLine(text)
        if (out is java.io.Flushable) out.flush()
        appendToFile(text)
    }

    private fun appendToFile(text: String) {
        val path = file ?: return
        if (fileBroken) return
        try {
            createPrivate(path)
            Files.write(path, (text + "\n").toByteArray(StandardCharsets.UTF_8), StandardOpenOption.APPEND)
        } catch (e: IOException) {
            fileBroken = true
            out.appendLine("No se puede escribir el registro $path (${e::class.simpleName}); sigue solo por aquí.")
        }
    }

    private fun createPrivate(path: Path) {
        if (Files.exists(path)) return
        path.parent?.let { Files.createDirectories(it) }
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } catch (_: FileAlreadyExistsException) {
            // Created between the check and here; appending to it is all that is needed.
        }
    }

    companion object {
        /** The file name of the log, next to the config file. */
        const val FILE_NAME = "bridge.log"

        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    }
}
