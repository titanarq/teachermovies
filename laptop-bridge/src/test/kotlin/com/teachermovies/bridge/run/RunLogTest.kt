package com.teachermovies.bridge.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneOffset

class RunLogTest {
    @get:Rule val tmpFolder = TemporaryFolder()

    private val clock = { Instant.parse("2026-09-28T10:15:30.042Z") }

    @Test
    fun `each line is timestamped and goes both to the output and to a 0600 file`() {
        val out = StringBuilder()
        val file = tmpFolder.root.toPath().resolve("sub/bridge.log")
        val log = RunLog(out, file, clock, ZoneOffset.UTC)
        log.line("uno")
        log.line("dos")
        assertEquals("2026-09-28 10:15:30.042 uno\n2026-09-28 10:15:30.042 dos\n", out.toString())
        assertEquals(out.toString(), Files.readString(file))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }

    @Test
    fun `an unwritable file is reported once and the output carries on`() {
        val out = StringBuilder()
        val blocker = tmpFolder.newFile("not-a-dir").toPath()
        val log = RunLog(out, blocker.resolve("bridge.log"), clock, ZoneOffset.UTC)
        log.line("uno")
        log.line("dos")
        val lines = out.lines().filter { it.isNotEmpty() }
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("No se puede escribir el registro"))
        assertTrue(lines[2].endsWith(" dos"))
    }
}
