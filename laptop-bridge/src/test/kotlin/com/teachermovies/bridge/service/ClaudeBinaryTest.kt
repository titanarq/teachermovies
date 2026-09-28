package com.teachermovies.bridge.service

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Where the Claude Code CLI is on this laptop (#278), as the `CLAUDE_BIN` the unit carries for the
 * transport of #276: the environment's own choice first, then a `claude` on `PATH`, then the usual
 * per-user install directory -- and an explicit `CLAUDE_BIN` that cannot run is reported rather than
 * quietly replaced by whatever the search would have found.
 */
class ClaudeBinaryTest {
    @get:Rule val tmpFolder = TemporaryFolder()

    private val home: Path
        get() = tmpFolder.root.toPath()

    /** An executable file named `claude` (or [name]) under this test's home. */
    private fun cli(
        directory: String = ".local/bin",
        name: String = ClaudeBinary.EXECUTABLE_NAME,
        executable: Boolean = true,
    ): Path {
        val path = home.resolve(directory).resolve(name)
        Files.createDirectories(requireNotNull(path.parent))
        Files.writeString(path, "#!/bin/sh\nexit 0\n")
        val mode = if (executable) "rwxr-xr-x" else "rw-r--r--"
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
        return path
    }

    private fun withClaudeBin(path: Path): (String) -> String? =
        { name -> if (name == ClaudeBinary.VARIABLE) path.toString() else null }

    private fun withPath(vararg directories: Path): (String) -> String? {
        val value = directories.joinToString(":") { it.toString() }
        return { name -> if (name == ServiceUnit.PATH_VARIABLE) value else null }
    }

    @Test
    fun `the CLAUDE_BIN of the environment wins over any search`() {
        val path = cli(directory = "otro/bin", name = "claude-mio")

        assertEquals(ClaudeLocation.Found(path), ClaudeBinary.locate(home, withClaudeBin(path)))
    }

    @Test
    fun `a tilde in CLAUDE_BIN is expanded against the home directory`() {
        val path = cli()
        val env = { name: String -> if (name == ClaudeBinary.VARIABLE) "~/.local/bin/claude" else null }

        assertEquals(ClaudeLocation.Found(path), ClaudeBinary.locate(home, env))
    }

    @Test
    fun `a CLAUDE_BIN naming nothing there is reported, not replaced by a search that would find one`() {
        cli()
        val missing = home.resolve("nada/claude")

        val location = ClaudeBinary.locate(home, withClaudeBin(missing))

        assertEquals(ClaudeLocation.Unusable(missing, exists = false), location)
    }

    @Test
    fun `a CLAUDE_BIN that cannot run is reported as such`() {
        val path = cli(executable = false)

        assertEquals(ClaudeLocation.Unusable(path, exists = true), ClaudeBinary.locate(home, withClaudeBin(path)))
    }

    @Test
    fun `with no CLAUDE_BIN, the first claude on PATH is the one the unit carries`() {
        cli(directory = "uno/bin")
        val first = cli(directory = "dos/bin")

        val location = ClaudeBinary.locate(home, withPath(home.resolve("dos/bin"), home.resolve("uno/bin")))

        assertEquals(ClaudeLocation.Found(first), location)
    }

    @Test
    fun `a claude in the per-user bin directory is found with no PATH at all`() {
        val path = cli()

        assertEquals(ClaudeLocation.Found(path), ClaudeBinary.locate(home) { null })
    }

    @Test
    fun `a laptop with no Claude CLI anywhere gives the unit no CLAUDE_BIN`() {
        assertEquals(ClaudeLocation.Absent, ClaudeBinary.locate(home) { null })
    }

    @Test
    fun `a claude on PATH that cannot run is not the one the unit carries`() {
        cli(directory = "uno/bin", executable = false)

        assertEquals(ClaudeLocation.Absent, ClaudeBinary.locate(home, withPath(home.resolve("uno/bin"))))
    }
}
