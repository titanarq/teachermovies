package com.teachermovies.bridge.cli

import com.teachermovies.bridge.config.BridgeConfigStore
import com.teachermovies.bridge.config.ConfigLoad
import com.teachermovies.bridge.service.BridgeLauncher
import com.teachermovies.bridge.service.ClaudeBinary
import com.teachermovies.bridge.service.ClaudeLocation
import com.teachermovies.bridge.service.LauncherLocation
import com.teachermovies.bridge.service.ServiceInstall
import com.teachermovies.bridge.service.ServiceInstaller
import com.teachermovies.bridge.service.ServiceUnit
import com.teachermovies.bridge.service.UnitRender
import com.teachermovies.bridge.service.UnitSpec
import java.nio.file.Path
import java.nio.file.Paths

/**
 * `teachermovies-bridge install-service` (#278, ADR-0005 §1): renders the systemd `--user` unit that
 * keeps `run` up, writes it where the user manager reads units from, and prints the three commands
 * that put it to work -- `daemon-reload`, `enable --now` and `loginctl enable-linger`, the last one
 * being what lets the bridge run with the laptop on and nobody logged into the desktop.
 *
 * It writes a unit and nothing else: it never starts, enables or reloads anything itself, because
 * those are the human's to run and a mistake in them is theirs to see. It prints the unit's path and
 * its `ExecStart`, never the token -- which stays in the config file the unit's own text names by
 * path only -- and says so, because "the unit carries no secret" is a promise worth reading where
 * the unit was just written.
 */
internal class InstallServiceCommand(
    private val out: Appendable,
    private val err: Appendable,
    private val home: Path,
    private val env: (String) -> String?,
    private val store: BridgeConfigStore,
    private val javaHome: Path = Paths.get(System.getProperty("java.home")),
) {
    fun run(command: Command.InstallService): Int =
        when (val launcher = BridgeLauncher.locate(command.exec, home)) {
            is LauncherLocation.Found -> {
                install(launcher.path)
            }

            is LauncherLocation.Unusable -> {
                fail(unusableLines(launcher))
            }

            is LauncherLocation.Underived -> {
                fail(underivedLines(launcher))
            }
        }

    private fun install(execStart: Path): Int {
        val claude = ClaudeBinary.locate(home, env)
        val claudeBin = (claude as? ClaudeLocation.Found)?.path
        val spec =
            UnitSpec(
                execStart = execStart,
                workingDirectory = home,
                path = ServiceUnit.pathValue(execStart, claudeBin, javaHome, env),
                tvLogsDir = ServiceUnit.tvLogsDir(home, env),
                claudeBin = claudeBin,
            )
        return when (val rendered = ServiceUnit.render(spec)) {
            is UnitRender.Refused -> {
                fail(refusedLines(rendered))
            }

            is UnitRender.Rendered -> {
                write(rendered.text, spec, claude)
            }
        }
    }

    private fun write(
        text: String,
        spec: UnitSpec,
        claude: ClaudeLocation,
    ): Int {
        val unitFile = ServiceUnit.unitFile(home, env)
        return when (val installed = ServiceInstaller(unitFile).install(text)) {
            is ServiceInstall.Installed -> {
                report(installed, spec, claude)
            }

            is ServiceInstall.Failed -> {
                fail(listOf("No se ha podido escribir $unitFile (${installed.reason})."))
            }
        }
    }

    private fun report(
        installed: ServiceInstall.Installed,
        spec: UnitSpec,
        claude: ClaudeLocation,
    ): Int {
        val verb = if (installed.replaced) "sustituida" else "escrita"
        out.appendLine("Unidad de usuario $verb en ${installed.path}.")
        out.appendLine("  Arranca: ${spec.execStart} run")
        out.appendLine(claudeLine(claude))
        out.appendLine("  Registro de la TV: ${spec.tvLogsDir}")
        out.appendLine("  La unidad no lleva ningún secreto: el token de la TV sigue en ${store.path}.")
        out.appendLine()
        if (!paired()) {
            out.appendLine(NO_PAIRING_HINT)
            out.appendLine()
        }
        out.appendLine("Para ponerla en marcha:")
        out.appendLine("  systemctl --user daemon-reload")
        out.appendLine("  systemctl --user enable --now ${ServiceUnit.UNIT_NAME}")
        out.appendLine("  loginctl enable-linger        # sigue en marcha sin sesión de escritorio")
        out.appendLine()
        out.appendLine("Para comprobarla:")
        out.appendLine("  ${spec.execStart} doctor")
        out.appendLine("  journalctl --user -u ${ServiceUnit.SERVICE_NAME} -f")
        out.appendLine()
        out.appendLine("Runbook: docs/runbooks/laptop-bridge.md")
        return ExitCode.OK
    }

    private fun claudeLine(claude: ClaudeLocation): String =
        when (claude) {
            is ClaudeLocation.Found -> {
                "  ${ClaudeBinary.VARIABLE}: ${claude.path}"
            }

            is ClaudeLocation.Unusable -> {
                val problem = if (claude.exists) "no es ejecutable" else "no existe"
                "  ${ClaudeBinary.VARIABLE}: fuera de la unidad; ${claude.path} $problem"
            }

            ClaudeLocation.Absent -> {
                "  ${ClaudeBinary.VARIABLE}: no se ha encontrado 'claude'; la unidad no lo lleva y" +
                    " los trabajos de IA fallarán hasta que lo instales"
            }
        }

    private fun unusableLines(launcher: LauncherLocation.Unusable): List<String> {
        val problem = if (launcher.exists) "no es ejecutable" else "no existe"
        return listOf("El script de arranque ${launcher.path} $problem.") + EXEC_HINT
    }

    private fun underivedLines(launcher: LauncherLocation.Underived): List<String> {
        val tried = if (launcher.tried == null) "" else " (se ha probado ${launcher.tried})"
        return listOf("No se ha podido deducir la ruta del script que arrancar$tried.") + EXEC_HINT
    }

    private fun refusedLines(refused: UnitRender.Refused): List<String> =
        listOf(
            "El valor de ${refused.field} lleva un carácter que systemd interpretaría: '${refused.character}'.",
            "No se ha escrito ninguna unidad.",
        )

    /** Whether `run` would have something to do: a stored pairing. Never the token itself. */
    private fun paired(): Boolean =
        when (val load = store.load()) {
            is ConfigLoad.Loaded -> {
                load.config.isPaired
            }

            ConfigLoad.Missing -> {
                false
            }

            is ConfigLoad.Corrupt -> {
                false
            }

            is ConfigLoad.Unreadable -> {
                false
            }
        }

    private fun fail(lines: List<String>): Int {
        lines.forEach { err.appendLine(it) }
        return ExitCode.FAILED
    }

    private companion object {
        /** What every launcher problem ends with: the way out is to name the script. */
        val EXEC_HINT =
            listOf(
                "Indícalo con '--exec <ruta>'; la escribe './gradlew :laptop-bridge:installDist', que deja",
                "el script en <instalación>/bin/${BridgeLauncher.SCRIPT_NAME}.",
            )

        val NO_PAIRING_HINT =
            "Antes de arrancarla: todavía no hay emparejamiento guardado, y 'run' acabaría en seguida."
    }
}
