package com.teachermovies.bridge.opensubtitles

import com.teachermovies.bridge.config.ConfigLocation
import com.teachermovies.bridge.config.toOctal
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The laptop's own OpenSubtitles credentials (#281, ADR-0005 §5): the consumer's API key and the
 * account the bridge logs in with. They live only on the laptop, in [CredentialsFile], and never
 * travel to the TV.
 *
 * [userAgent] is optional: OpenSubtitles asks every consumer for a `User-Agent` naming the
 * application, and [OpenSubtitlesApi.DEFAULT_USER_AGENT] is used when the file does not set one.
 * [toString] redacts everything but the user agent, so interpolating this value by mistake prints
 * no secret.
 */
class OpenSubtitlesCredentials(
    val apiKey: String,
    val username: String,
    val password: String,
    val userAgent: String? = null,
) {
    override fun toString(): String =
        "OpenSubtitlesCredentials(apiKey=<redacted>, username=<redacted>, password=<redacted>, userAgent=$userAgent)"
}

/** Outcome of [CredentialsFile.load]. No case carries a line of the file. */
sealed interface CredentialsLoad {
    data object Missing : CredentialsLoad

    data class Loaded(
        val credentials: OpenSubtitlesCredentials,
    ) : CredentialsLoad

    /** The file exists but lacks [missingKeys] (key names only, never a value). */
    data class Incomplete(
        val missingKeys: List<String>,
    ) : CredentialsLoad

    /** [reason] is an exception class name. */
    data class Unreadable(
        val reason: String,
    ) : CredentialsLoad
}

/**
 * The env file holding [OpenSubtitlesCredentials] (#281): `KEY=VALUE` lines, `#` comments, blank
 * lines, an optional leading `export ` and optional matching single or double quotes around a
 * value. Keys: [API_KEY], [USERNAME], [PASSWORD] (required) and [USER_AGENT] (optional).
 *
 * The file is a secret: nothing here returns, prints or puts in a failure reason any of its lines,
 * and `doctor` only asks this class whether the file exists and what its [permissions] are.
 */
class CredentialsFile(
    given: Path,
) {
    val path: Path = given.toAbsolutePath().normalize()

    fun exists(): Boolean = Files.exists(path)

    /** The file's permissions as four octal digits (`"0600"`), or null with no readable file. */
    fun permissions(): String? {
        if (!exists()) return null
        return try {
            Files.getPosixFilePermissions(path).toOctal()
        } catch (_: IOException) {
            null
        }
    }

    fun load(): CredentialsLoad {
        if (!exists()) return CredentialsLoad.Missing
        val lines =
            try {
                Files.readAllLines(path)
            } catch (e: IOException) {
                return CredentialsLoad.Unreadable(e::class.simpleName ?: "error")
            }
        val values = parse(lines)
        val missing = REQUIRED_KEYS.filter { values[it].isNullOrEmpty() }
        if (missing.isNotEmpty()) return CredentialsLoad.Incomplete(missing)
        return CredentialsLoad.Loaded(
            OpenSubtitlesCredentials(
                apiKey = values.getValue(API_KEY),
                username = values.getValue(USERNAME),
                password = values.getValue(PASSWORD),
                userAgent = values[USER_AGENT]?.takeIf { it.isNotBlank() },
            ),
        )
    }

    companion object {
        const val API_KEY = "OPENSUBTITLES_API_KEY"
        const val USERNAME = "OPENSUBTITLES_USERNAME"
        const val PASSWORD = "OPENSUBTITLES_PASSWORD"
        const val USER_AGENT = "OPENSUBTITLES_USER_AGENT"

        /** Overrides [defaultPath] with the path of another env file. */
        const val PATH_VARIABLE = "TEACHERMOVIES_OPENSUBTITLES_ENV"

        /** The directory and file name inside the bridge's config directory. */
        const val SECRETS_DIR = ".secrets"
        const val FILE_NAME = "opensubtitles.env"

        private val REQUIRED_KEYS = listOf(API_KEY, USERNAME, PASSWORD)

        private const val EXPORT_PREFIX = "export "

        /**
         * `$TEACHERMOVIES_OPENSUBTITLES_ENV` when set and non-blank, otherwise
         * `<config dir>/.secrets/opensubtitles.env` -- the bridge's config directory
         * ([ConfigLocation.configDir]), independent of `--config`, which names only the config file.
         */
        fun defaultPath(
            home: Path,
            env: (String) -> String?,
        ): Path {
            val explicit = env(PATH_VARIABLE)?.takeIf { it.isNotBlank() }
            if (explicit != null) return ConfigLocation.expand(explicit, home)
            return ConfigLocation.configDir(home, env).resolve(SECRETS_DIR).resolve(FILE_NAME)
        }

        /** The `KEY=VALUE` pairs of [lines]; a later key wins, a line without `=` is ignored. */
        internal fun parse(lines: List<String>): Map<String, String> {
            val values = mutableMapOf<String, String>()
            for (raw in lines) {
                val line = raw.trim().removePrefix(EXPORT_PREFIX).trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val equals = line.indexOf('=')
                if (equals <= 0) continue
                val key = line.substring(0, equals).trim()
                values[key] = unquote(line.substring(equals + 1).trim())
            }
            return values
        }

        private fun unquote(value: String): String {
            val quoted =
                value.length >= 2 &&
                    (value.first() == '"' || value.first() == '\'') &&
                    value.last() == value.first()
            return if (quoted) value.substring(1, value.length - 1) else value
        }
    }
}
