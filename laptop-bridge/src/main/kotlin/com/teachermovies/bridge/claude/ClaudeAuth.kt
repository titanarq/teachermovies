package com.teachermovies.bridge.claude

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What `claude auth status --json` said (#276), for `doctor`. */
sealed interface AuthStatus {
    /** Logged in: [authMethod] (e.g. `claude.ai`) and [subscriptionType] (e.g. `max`) as the CLI names them. */
    data class LoggedIn(
        val authMethod: String?,
        val subscriptionType: String?,
    ) : AuthStatus

    data object LoggedOut : AuthStatus

    /** The command could not be run or answered something else; [stderr] is its last lines. */
    data class Failed(
        val reason: String,
        val stderr: List<String>,
    ) : AuthStatus
}

/**
 * Asks the Claude Code CLI who it is logged in as -- no model call, nothing spent -- with the same
 * stripped environment as [ClaudeCliTransport], so an `ANTHROPIC_API_KEY` in the bridge's own
 * environment cannot make the answer look better than what the transport will get.
 */
object ClaudeAuth {
    private val JSON = Json { ignoreUnknownKeys = true }
    private const val STDERR_LINES = 5

    fun status(
        executable: Path,
        baseEnvironment: Map<String, String> = System.getenv(),
        timeout: Duration = 10.seconds,
    ): AuthStatus {
        val stdout = Files.createTempFile("claude-auth", ".out")
        val stderr = Files.createTempFile("claude-auth", ".err")
        try {
            val builder =
                ProcessBuilder(ClaudeCommandLine.authStatusArgv(executable))
                    .redirectInput(ProcessBuilder.Redirect.from(NULL_DEVICE))
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile())
            builder.environment().clear()
            builder.environment().putAll(ClaudeCommandLine.childEnvironment(baseEnvironment))
            val process =
                try {
                    builder.start()
                } catch (e: IOException) {
                    return AuthStatus.Failed("no se puede ejecutar (${e::class.simpleName})", emptyList())
                }
            if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return AuthStatus.Failed("no ha respondido en ${timeout.inWholeSeconds} s", tail(stderr))
            }
            return parse(Files.readString(stdout, StandardCharsets.UTF_8), process.exitValue(), tail(stderr))
        } finally {
            Files.deleteIfExists(stdout)
            Files.deleteIfExists(stderr)
        }
    }

    /** The JSON answer decides, whatever the exit code: a logged-out CLI may exit non-zero. */
    internal fun parse(
        stdout: String,
        exitCode: Int,
        stderr: List<String>,
    ): AuthStatus {
        val answer =
            try {
                JSON.parseToJsonElement(stdout) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: return AuthStatus.Failed("respuesta ilegible (código $exitCode)", stderr)
        val loggedIn =
            (answer["loggedIn"] as? JsonPrimitive)?.booleanOrNull
                ?: return AuthStatus.Failed("respuesta sin 'loggedIn' (código $exitCode)", stderr)
        if (!loggedIn) return AuthStatus.LoggedOut
        return AuthStatus.LoggedIn(answer.string("authMethod"), answer.string("subscriptionType"))
    }

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun tail(file: Path): List<String> =
        try {
            Files.readAllLines(file, StandardCharsets.UTF_8).filter { it.isNotBlank() }.takeLast(STDERR_LINES)
        } catch (_: IOException) {
            emptyList()
        }

    private val NULL_DEVICE = java.io.File("/dev/null")
}
