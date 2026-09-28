package com.teachermovies.bridge.claude

import com.teachermovies.bridge.config.ConfigLocation
import java.nio.file.Path

/**
 * The exact Claude Code invocation of ADR-0005 §3: a headless, long-lived process reading one JSON
 * turn per stdin line and writing JSON events on stdout, with every built-in capability switched
 * off -- no tool, no MCP server, no settings file, no slash command, no session transcript -- so
 * it is a pure text responder and never a coding agent loose on this laptop.
 */
object ClaudeCommandLine {
    /**
     * Removed from the child's environment, whatever the bridge's own holds: either would bill the
     * process to an API key instead of the human's subscription (ADR-0005 §3, the parent's decision 1).
     */
    val STRIPPED_ENVIRONMENT: Set<String> = setOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN")

    /**
     * The argv of one process. [systemPromptFile] must be absolute: the CLI resolves a relative one
     * against its own working directory, which is not where the bridge wrote it (the howto's
     * `~`-doubling bug), and fails with "system prompt file not found".
     */
    fun argv(
        executable: Path,
        settings: ClaudeSettings,
        systemPromptFile: Path,
    ): List<String> {
        require(executable.isAbsolute) { "the claude executable must be an absolute path" }
        require(systemPromptFile.isAbsolute) { "the system prompt file must be an absolute path" }
        return listOf(
            executable.toString(),
            "-p",
            "--input-format",
            "stream-json",
            "--output-format",
            "stream-json",
            // Required by the CLI for stream-json output in print mode.
            "--verbose",
            "--model",
            settings.model,
            "--effort",
            settings.effort,
            "--system-prompt-file",
            systemPromptFile.toString(),
            "--tools",
            "",
            "--strict-mcp-config",
            "--setting-sources",
            "",
            "--disable-slash-commands",
            "--no-session-persistence",
        )
    }

    /** `claude auth status --json`: who the CLI is logged in as, without a model call. */
    fun authStatusArgv(executable: Path): List<String> = listOf(executable.toString(), "auth", "status", "--json")

    /** [base] without [STRIPPED_ENVIRONMENT]. */
    fun childEnvironment(base: Map<String, String>): Map<String, String> = base - STRIPPED_ENVIRONMENT
}

/**
 * The directory the bridge keeps its Claude processes' files in: `teachermovies-bridge/claude`
 * under `$XDG_CACHE_HOME` when set and non-blank, otherwise under `~/.cache`; always absolute, so
 * the same path serves as a process's working directory and as the base of the files named in its
 * argv. Nothing here outlives a process except the [stderrTail] files `doctor` shows.
 */
class ClaudeDirs(
    given: Path,
) {
    val root: Path = given.toAbsolutePath().normalize()

    /** Parent of every process's own empty working directory. */
    val workDirs: Path get() = root.resolve("cwd")

    /** Where each process's system prompt file is written, outside its working directory. */
    val prompts: Path get() = root.resolve("prompts")

    /** The stderr tail of the last [kind] process that ended badly, for `doctor`. */
    fun stderrTail(kind: String): Path = root.resolve("$kind$STDERR_SUFFIX")

    companion object {
        const val STDERR_SUFFIX = ".stderr.log"
        private const val CACHE_HOME_VARIABLE = "XDG_CACHE_HOME"

        fun default(
            home: Path,
            env: (String) -> String?,
        ): ClaudeDirs {
            val xdgCache = env(CACHE_HOME_VARIABLE)?.takeIf { it.isNotBlank() }
            val cacheHome = if (xdgCache == null) home.resolve(".cache") else ConfigLocation.expand(xdgCache, home)
            return ClaudeDirs(cacheHome.resolve("teachermovies-bridge").resolve("claude"))
        }
    }
}
