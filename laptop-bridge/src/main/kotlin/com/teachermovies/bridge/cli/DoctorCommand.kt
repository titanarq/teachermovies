package com.teachermovies.bridge.cli

import com.teachermovies.bridge.claude.AuthStatus
import com.teachermovies.bridge.claude.ClaudeDirs
import com.teachermovies.bridge.claude.ClaudeSettings
import com.teachermovies.bridge.claude.ClaudeSettingsFile
import com.teachermovies.bridge.claude.ClaudeSettingsLoad
import com.teachermovies.bridge.config.BridgeConfig
import com.teachermovies.bridge.config.BridgeConfigStore
import com.teachermovies.bridge.config.ConfigLoad
import com.teachermovies.bridge.opensubtitles.CredentialsFile
import com.teachermovies.bridge.service.ClaudeBinary
import com.teachermovies.bridge.service.ClaudeLocation
import com.teachermovies.bridge.tv.ApiResult
import com.teachermovies.bridge.tv.TvApi
import com.teachermovies.bridge.tv.TvStatus
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * `teachermovies-bridge doctor` (#271): answers "is this bridge installed, paired and able to talk
 * to the TV?" without anybody reading a config file or a log by hand.
 *
 * The report goes to [out] one line per check, each `OK` or `FALLO`, and the exit code is
 * [ExitCode.OK] only when every check passed, so a script -- or the systemd unit of #278 -- can act
 * on it. Checks run in the order in which one failing explains the next (file, its permissions, the
 * pairing, then the two calls to the TV) and stop after a missing or unreadable file, since without
 * it there is nothing to call with. The token is reported as present or absent and never printed.
 *
 * The OpenSubtitles credentials file (#281) is checked last and whatever happened to the config:
 * only that it exists and that its permissions are `0600`. `doctor` never opens it, so none of its
 * contents -- not even whether it is complete -- can reach the report.
 *
 * The Claude Code CLI (#276) comes after it, also whatever happened to the config: the binary the
 * transport would start ([ClaudeBinary]), `claude auth status` -- a login, never a model call --
 * and whether `claude.json` is usable, followed by the stderr tail of the last Claude process that
 * died, when there is one, as information rather than a check.
 */
internal class DoctorCommand(
    private val out: Appendable,
    private val store: BridgeConfigStore,
    private val api: TvApi,
    private val credentials: CredentialsFile,
    private val claude: ClaudeChecks,
) {
    private var problems = 0

    suspend fun run(): Int {
        val config = reportConfig()
        if (config == null) return finish()
        reportPermissions()
        reportPairing(config)
        val tvUrl = config.tvUrl
        if (tvUrl != null) reportStatus(tvUrl)
        val token = config.token
        if (tvUrl != null && token != null) reportLogs(tvUrl, token)
        return finish()
    }

    /** The stored config, or null once the reason it is unusable has been reported. */
    private fun reportConfig(): BridgeConfig? =
        when (val load = store.load()) {
            ConfigLoad.Missing -> {
                problem("configuración", "no existe ${store.path}", PAIR_HINT)
                null
            }

            is ConfigLoad.Loaded -> {
                ok("configuración", store.path.toString())
                load.config
            }

            is ConfigLoad.Corrupt -> {
                problem("configuración", "${store.path} no se puede leer (${load.reason})", PAIR_HINT)
                null
            }

            is ConfigLoad.Unreadable -> {
                problem("configuración", "${store.path} no se puede leer (${load.reason})", PAIR_HINT)
                null
            }
        }

    private fun reportPermissions() {
        when (val permissions = store.permissions()) {
            null -> {
                problem("permisos", "no se han podido leer los de ${store.path}")
            }

            EXPECTED_PERMISSIONS -> {
                ok("permisos", permissions)
            }

            else -> {
                problem(
                    "permisos",
                    "$permissions en ${store.path}, y ese fichero guarda el token de la TV",
                    "chmod $EXPECTED_PERMISSIONS ${store.path}",
                )
            }
        }
    }

    private fun reportPairing(config: BridgeConfig) {
        if (config.isPaired) {
            ok("emparejado", "${config.tvUrl} (ámbito '${TvApi.BRIDGE_SCOPE}', token guardado y oculto)")
            return
        }
        problem("emparejado", "la configuración no guarda la URL de la TV y un token a la vez", PAIR_HINT)
    }

    /** `GET /api/status` is public, so it answers even with a token the TV has forgotten. */
    private suspend fun reportStatus(tvUrl: String) {
        when (val result = api.status(tvUrl)) {
            is ApiResult.Success -> {
                ok("TV /api/status", statusText(result.value))
            }

            is ApiResult.Failure -> {
                problem("TV /api/status", describeFailure(result.failure), PAIR_HINT)
            }
        }
    }

    /**
     * `GET /api/logs` with the stored token, one line only: a 200 proves the token exists, is a
     * bridge token and has not been revoked, which is the thing `doctor` cannot check locally.
     */
    private suspend fun reportLogs(
        tvUrl: String,
        token: String,
    ) {
        val result = api.logs(tvUrl, token, since = 0L, level = null, limit = 1)
        when (result) {
            is ApiResult.Success -> {
                val bootId = result.value.bootId.take(BOOT_ID_LENGTH)
                ok("TV /api/logs", "el token es válido (arranque de la TV $bootId)")
            }

            is ApiResult.Failure -> {
                problem("TV /api/logs", describeFailure(result.failure), PAIR_HINT)
            }
        }
    }

    private fun statusText(status: TvStatus): String =
        "versión ${status.version}, motor ${status.engine}, torrents ${status.torrents}"

    /** Existence and mode only: the file is never read (#281). */
    private fun reportCredentials() {
        val path = credentials.path
        if (!credentials.exists()) {
            problem("credenciales OpenSubtitles", "no existe $path", CREDENTIALS_HINT)
            return
        }
        when (val permissions = credentials.permissions()) {
            null -> {
                problem("credenciales OpenSubtitles", "no se han podido leer los permisos de $path")
            }

            EXPECTED_PERMISSIONS -> {
                ok("credenciales OpenSubtitles", "$path (permisos $permissions, contenido no mostrado)")
            }

            else -> {
                problem(
                    "credenciales OpenSubtitles",
                    "$permissions en $path, y ese fichero guarda la cuenta de OpenSubtitles",
                    "chmod $EXPECTED_PERMISSIONS $path",
                )
            }
        }
    }

    private fun reportClaude() {
        when (val location = claude.location) {
            is ClaudeLocation.Found -> {
                ok("Claude Code", location.path.toString())
                reportAuth(location.path)
            }

            is ClaudeLocation.Unusable -> {
                val why = if (location.exists) "no es ejecutable" else "no existe"
                problem("Claude Code", "${ClaudeBinary.VARIABLE}=${location.path} $why", CLAUDE_BIN_HINT)
            }

            ClaudeLocation.Absent -> {
                problem(
                    "Claude Code",
                    "no se encuentra '${ClaudeBinary.EXECUTABLE_NAME}' en el PATH ni en ~/.local/bin",
                    CLAUDE_BIN_HINT,
                )
            }
        }
        reportClaudeSettings()
        reportStderrTails()
    }

    private fun reportAuth(executable: Path) {
        when (val status = claude.auth(executable)) {
            is AuthStatus.LoggedIn -> {
                val method = status.authMethod ?: "método desconocido"
                val plan = status.subscriptionType?.let { ", plan $it" }.orEmpty()
                ok("sesión de Claude Code", "iniciada ($method$plan)")
            }

            AuthStatus.LoggedOut -> {
                problem("sesión de Claude Code", "no hay sesión iniciada", "inicia sesión con: $executable auth login")
            }

            is AuthStatus.Failed -> {
                problem("sesión de Claude Code", "'claude auth status' ${status.reason}")
                status.stderr.forEach { out.appendLine("$HINT_INDENT  $it") }
            }
        }
    }

    private fun reportClaudeSettings() {
        val file = claude.settings
        when (val load = file.load()) {
            ClaudeSettingsLoad.Defaults -> {
                ok("ajustes de Claude", "${settingsText(ClaudeSettings())} (predeterminados; ${file.path} no existe)")
            }

            is ClaudeSettingsLoad.Loaded -> {
                ok("ajustes de Claude", "${settingsText(load.settings)} (${file.path})")
            }

            is ClaudeSettingsLoad.Corrupt -> {
                problem("ajustes de Claude", "${file.path} no se puede usar (${load.reason})", SETTINGS_HINT)
            }

            is ClaudeSettingsLoad.Unreadable -> {
                problem("ajustes de Claude", "${file.path} no se puede leer (${load.reason})", SETTINGS_HINT)
            }
        }
    }

    private fun settingsText(settings: ClaudeSettings): String =
        "modelo ${settings.model}, esfuerzo ${settings.effort}, tope ${settings.dailyJobCap} trabajos/día"

    /** The stderr each job kind's last dead process left behind (#276): information, not a check. */
    private fun reportStderrTails() {
        val files =
            try {
                Files.list(claude.dirs.root).use { entries ->
                    entries.filter { it.fileName.toString().endsWith(ClaudeDirs.STDERR_SUFFIX) }.sorted().toList()
                }
            } catch (_: IOException) {
                return
            }
        for (file in files) {
            val lines =
                try {
                    Files.readAllLines(file, StandardCharsets.UTF_8).filter { it.isNotBlank() }
                } catch (_: IOException) {
                    continue
                }
            out.appendLine("$INFO_LABEL último stderr de Claude Code ($file):")
            lines.takeLast(STDERR_LINES).forEach { out.appendLine("$HINT_INDENT  $it") }
        }
    }

    private fun finish(): Int {
        reportCredentials()
        reportClaude()
        if (problems == 0) {
            out.appendLine("doctor: todo correcto")
            return ExitCode.OK
        }
        val noun = if (problems == 1) "problema" else "problemas"
        out.appendLine("doctor: $problems $noun")
        return ExitCode.FAILED
    }

    private fun ok(
        label: String,
        detail: String,
    ) {
        out.appendLine("$OK_LABEL $label: $detail")
    }

    private fun problem(
        label: String,
        detail: String,
        hint: String? = null,
    ) {
        problems += 1
        out.appendLine("$PROBLEM_LABEL $label: $detail")
        if (hint != null) out.appendLine("$HINT_INDENT-> $hint")
    }

    private companion object {
        const val EXPECTED_PERMISSIONS = "0600"
        const val BOOT_ID_LENGTH = 8
        const val OK_LABEL = "OK   "
        const val PROBLEM_LABEL = "FALLO"
        const val INFO_LABEL = "INFO "
        const val STDERR_LINES = 10

        const val CLAUDE_BIN_HINT =
            "instala Claude Code (https://claude.com/claude-code) o pon la ruta absoluta del CLI en ${ClaudeBinary.VARIABLE}"

        const val SETTINGS_HINT =
            "corrige o borra ese fichero: sin él se usan modelo ${ClaudeSettings.DEFAULT_MODEL}, " +
                "esfuerzo ${ClaudeSettings.DEFAULT_EFFORT} y ${ClaudeSettings.DEFAULT_DAILY_JOB_CAP} trabajos/día"
        const val HINT_INDENT = "      "

        const val PAIR_HINT =
            "empareja con: teachermovies-bridge pair --url http://<ip-de-la-tv>:8787 --pin <pin>"

        const val CREDENTIALS_HINT =
            "crea ese fichero con ${CredentialsFile.API_KEY}, ${CredentialsFile.USERNAME} y " +
                "${CredentialsFile.PASSWORD}, y dale permisos $EXPECTED_PERMISSIONS"
    }
}

/**
 * What `doctor` needs to check the Claude Code CLI (#276): where the binary is, the settings file,
 * the transport's directory (for stderr tails) and how to ask `claude auth status` -- injected so a
 * test drives a fake `claude`.
 */
internal class ClaudeChecks(
    val location: ClaudeLocation,
    val settings: ClaudeSettingsFile,
    val dirs: ClaudeDirs,
    val auth: (Path) -> AuthStatus,
)
