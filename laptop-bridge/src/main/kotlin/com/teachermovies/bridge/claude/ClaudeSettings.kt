package com.teachermovies.bridge.claude

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * How the bridge drives the Claude Code CLI (#276, ADR-0005 §3): the model and effort every process
 * is started with (Sonnet, low effort: tuned for speed), how many jobs a day may reach Claude at all,
 * how long one turn may take before its process is killed, and after how many jobs a process is
 * replaced by a fresh one, so the conversation it carries never grows without bound.
 *
 * Read from [ClaudeSettingsFile], which the human may edit by hand; every field has a default, so
 * a missing file or a missing field is the defaults.
 */
@Serializable
data class ClaudeSettings(
    val model: String = DEFAULT_MODEL,
    val effort: String = DEFAULT_EFFORT,
    val dailyJobCap: Int = DEFAULT_DAILY_JOB_CAP,
    val turnTimeoutSeconds: Long = DEFAULT_TURN_TIMEOUT_SECONDS,
    val rotateAfterTurns: Int = DEFAULT_ROTATE_AFTER_TURNS,
) {
    /** The first field that cannot be used, or null: the CLI would misread or refuse it. */
    fun problem(): String? =
        when {
            !isArgument(model) -> "model"
            !isArgument(effort) -> "effort"
            dailyJobCap < 1 -> "dailyJobCap"
            turnTimeoutSeconds < 1 -> "turnTimeoutSeconds"
            rotateAfterTurns < 1 -> "rotateAfterTurns"
            else -> null
        }

    /** A value that goes into the CLI's argv as the argument of a flag, never as a flag itself. */
    private fun isArgument(value: String): Boolean =
        value.isNotBlank() && !value.startsWith("-") && value.none { it.isWhitespace() }

    companion object {
        const val DEFAULT_MODEL = "sonnet"
        const val DEFAULT_EFFORT = "low"

        /** ADR-0005 §3: "a daily cap (default ~300 requests, configurable)". */
        const val DEFAULT_DAILY_JOB_CAP = 300
        const val DEFAULT_TURN_TIMEOUT_SECONDS = 60L
        const val DEFAULT_ROTATE_AFTER_TURNS = 25
    }
}

/** Outcome of [ClaudeSettingsFile.load]. */
sealed interface ClaudeSettingsLoad {
    /** No file: every setting is its default. */
    data object Defaults : ClaudeSettingsLoad

    data class Loaded(
        val settings: ClaudeSettings,
    ) : ClaudeSettingsLoad

    /** The file is not JSON of this shape, or [reason] names the field whose value cannot be used. */
    data class Corrupt(
        val reason: String,
    ) : ClaudeSettingsLoad

    /** [reason] is an exception class name. */
    data class Unreadable(
        val reason: String,
    ) : ClaudeSettingsLoad
}

/**
 * `claude.json`, next to the bridge's `config.json` ([besideConfig]): the [ClaudeSettings] a human
 * wants other than the defaults. It holds no secret -- the CLI's own login is the only credential
 * involved, and it never passes through the bridge -- so it is read, not guarded like the config.
 */
class ClaudeSettingsFile(
    given: Path,
) {
    val path: Path = given.toAbsolutePath().normalize()

    fun load(): ClaudeSettingsLoad {
        val text =
            try {
                Files.readString(path)
            } catch (_: NoSuchFileException) {
                return ClaudeSettingsLoad.Defaults
            } catch (e: IOException) {
                return ClaudeSettingsLoad.Unreadable(e::class.simpleName ?: "IOException")
            }
        val settings =
            try {
                JSON.decodeFromString(ClaudeSettings.serializer(), text)
            } catch (e: SerializationException) {
                return ClaudeSettingsLoad.Corrupt(e::class.simpleName ?: "SerializationException")
            } catch (e: IllegalArgumentException) {
                return ClaudeSettingsLoad.Corrupt(e::class.simpleName ?: "IllegalArgumentException")
            }
        val problem = settings.problem()
        if (problem != null) return ClaudeSettingsLoad.Corrupt("valor no válido en '$problem'")
        return ClaudeSettingsLoad.Loaded(settings)
    }

    companion object {
        const val FILE_NAME = "claude.json"

        /** The settings file of the bridge whose config file is [configFile]. */
        fun besideConfig(configFile: Path): ClaudeSettingsFile =
            ClaudeSettingsFile(configFile.resolveSibling(FILE_NAME))

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
