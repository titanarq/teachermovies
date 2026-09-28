package com.teachermovies.bridge.service

import com.teachermovies.bridge.config.ConfigLocation
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where the Claude Code CLI lives on this laptop (#278), as the `CLAUDE_BIN` the unit carries: the
 * Claude transport of #276 reads that variable, and a systemd user manager -- with linger, so with
 * no desktop session behind it -- has neither the shell's `PATH` nor its aliases to find the CLI by
 * itself.
 *
 * Resolution order: the installing shell's own `CLAUDE_BIN` when it names one, then a `claude` on
 * its `PATH`, then `~/.local/bin/claude`. An explicit `CLAUDE_BIN` that cannot run is reported
 * ([ClaudeLocation.Unusable]) rather than quietly replaced by whatever the `PATH` search would have
 * found: the human named that file, so its being wrong is the finding.
 */
internal object ClaudeBinary {
    /** The variable the unit sets and #276's transport reads. */
    const val VARIABLE = "CLAUDE_BIN"

    /** The CLI's own executable name. */
    const val EXECUTABLE_NAME = "claude"

    private const val PATH_VARIABLE = "PATH"
    private const val PATH_SEPARATOR = ":"
    private const val LOCAL_DIR = ".local"
    private const val BIN_DIR = "bin"

    /** The CLI this laptop offers the bridge, or why it offers none. */
    fun locate(
        home: Path,
        env: (String) -> String?,
    ): ClaudeLocation {
        val override = env(VARIABLE)?.takeIf { it.isNotBlank() }
        if (override != null) return unusableOrFound(ConfigLocation.expand(override, home))
        val found = onPath(home, env) ?: fallback(home)
        if (found == null) return ClaudeLocation.Absent
        return ClaudeLocation.Found(found)
    }

    private fun unusableOrFound(path: Path): ClaudeLocation =
        when {
            !Files.exists(path) -> ClaudeLocation.Unusable(path, exists = false)
            !Files.isExecutable(path) -> ClaudeLocation.Unusable(path, exists = true)
            else -> ClaudeLocation.Found(path)
        }

    /** The first `claude` in [env]'s `PATH` that can be run; a `PATH` entry is `~`-expanded. */
    private fun onPath(
        home: Path,
        env: (String) -> String?,
    ): Path? {
        val directories = env(PATH_VARIABLE).orEmpty().split(PATH_SEPARATOR)
        for (directory in directories) {
            if (directory.isBlank()) continue
            val candidate = ConfigLocation.expand(directory, home).resolve(EXECUTABLE_NAME)
            if (Files.isExecutable(candidate)) return candidate
        }
        return null
    }

    /** `~/.local/bin/claude`, where a per-user install of the CLI usually is. */
    private fun fallback(home: Path): Path? {
        val candidate = home.resolve(LOCAL_DIR).resolve(BIN_DIR).resolve(EXECUTABLE_NAME)
        return if (Files.isExecutable(candidate)) candidate else null
    }
}

/** What [ClaudeBinary.locate] found. */
internal sealed interface ClaudeLocation {
    /** The CLI to write into the unit's `CLAUDE_BIN`. */
    data class Found(
        val path: Path,
    ) : ClaudeLocation

    /**
     * `CLAUDE_BIN` named a file that cannot run: [exists] is false when there is nothing at [path]
     * at all. The unit carries no `CLAUDE_BIN` rather than this one.
     */
    data class Unusable(
        val path: Path,
        val exists: Boolean,
    ) : ClaudeLocation

    /** No CLI anywhere this bridge looked: the unit carries no `CLAUDE_BIN`. */
    data object Absent : ClaudeLocation
}
