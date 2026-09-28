package com.teachermovies.bridge.service

import com.teachermovies.bridge.config.toOctal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Writing the unit where systemd's user manager reads it (#278): an install is idempotent, so
 * upgrading the bridge over an earlier unit leaves one file behind, with the permissions a unit file
 * is meant to have, and a filesystem that refuses the write comes back as a [ServiceInstall.Failed]
 * rather than as an exception.
 */
class ServiceInstallerTest {
    @get:Rule val tmpFolder = TemporaryFolder()

    private val home: Path
        get() = tmpFolder.root.toPath()

    private val unitFile: Path
        get() = ServiceUnit.unitFile(home) { null }

    private val text = "[Unit]\nDescription=prueba\n"

    private fun install(
        target: Path = unitFile,
        content: String = text,
    ): ServiceInstall = ServiceInstaller(target).install(content)

    private fun mode(path: Path): String = Files.getPosixFilePermissions(path).toOctal()

    @Test
    fun `the unit lands in the user unit directory, world-readable, in a private directory`() {
        val installed = install()

        assertTrue(installed is ServiceInstall.Installed)
        assertFalse((installed as ServiceInstall.Installed).replaced)
        assertEquals(text, Files.readString(unitFile))
        assertEquals("0644", mode(unitFile))
        assertEquals("0700", mode(requireNotNull(unitFile.parent)))
    }

    @Test
    fun `installing again replaces the unit and says so`() {
        install()

        val replaced = install(content = "[Unit]\nDescription=otra\n")

        assertTrue(replaced is ServiceInstall.Installed)
        assertTrue((replaced as ServiceInstall.Installed).replaced)
        assertEquals("[Unit]\nDescription=otra\n", Files.readString(unitFile))
        assertEquals("0644", mode(unitFile))
    }

    @Test
    fun `a unit file whose permissions had been loosened is written back to 0644`() {
        Files.createDirectories(requireNotNull(unitFile.parent))
        Files.writeString(unitFile, "viejo")
        Files.setPosixFilePermissions(unitFile, PosixFilePermissions.fromString("rw-rw-rw-"))

        install()

        assertEquals("0644", mode(unitFile))
    }

    @Test
    fun `a unit directory that cannot exist is a failure with the exception's own name`() {
        val blocking = home.resolve("bloqueo")
        Files.writeString(blocking, "no soy un directorio")

        val installed = install(target = blocking.resolve("systemd/user/teachermovies-bridge.service"))

        assertTrue(installed is ServiceInstall.Failed)
        assertEquals("FileAlreadyExistsException", (installed as ServiceInstall.Failed).reason)
    }
}
