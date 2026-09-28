package com.teachermovies.bridge.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Which script the unit's `ExecStart` names (#278): an absolute path, because systemd refuses any
 * other and a service has no shell to resolve a bare `teachermovies-bridge` against.
 */
class BridgeLauncherTest {
    @get:Rule val tmpFolder = TemporaryFolder()

    private val home: Path
        get() = tmpFolder.root.toPath()

    /** A start script under this test's home, executable unless told otherwise. */
    private fun script(
        directory: String = "bridge/bin",
        executable: Boolean = true,
    ): Path {
        val path = home.resolve(directory).resolve(BridgeLauncher.SCRIPT_NAME)
        Files.createDirectories(requireNotNull(path.parent))
        Files.writeString(path, "#!/bin/sh\nexit 0\n")
        val mode = if (executable) "rwxr-xr-x" else "rw-r--r--"
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
        return path
    }

    @Test
    fun `an --exec that can run is the script the unit launches`() {
        val path = script()

        assertEquals(LauncherLocation.Found(path), BridgeLauncher.locate(path.toString(), home))
    }

    @Test
    fun `a tilde in --exec is this laptop's home directory`() {
        val path = script()
        val spec = "~/bridge/bin/${BridgeLauncher.SCRIPT_NAME}"

        assertEquals(LauncherLocation.Found(path), BridgeLauncher.locate(spec, home))
    }

    @Test
    fun `--exec naming nothing there is reported as missing, with the path it looked at`() {
        val missing = home.resolve("nada/bin").resolve(BridgeLauncher.SCRIPT_NAME)

        val location = BridgeLauncher.locate(missing.toString(), home)

        assertEquals(LauncherLocation.Unusable(missing, exists = false), location)
    }

    @Test
    fun `--exec naming a file that cannot run is reported as such`() {
        val path = script(executable = false)

        assertEquals(LauncherLocation.Unusable(path, exists = true), BridgeLauncher.locate(path.toString(), home))
    }

    @Test
    fun `with no --exec, a program run out of a build directory derives no distribution`() {
        val location = BridgeLauncher.locate(null, home)

        assertTrue("expected Underived, got $location", location is LauncherLocation.Underived)
    }

    @Test
    fun `a blank --exec is no --exec at all`() {
        assertTrue(BridgeLauncher.locate("   ", home) is LauncherLocation.Underived)
    }
}
