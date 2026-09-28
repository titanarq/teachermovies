package com.teachermovies.bridge.service

import com.teachermovies.bridge.config.ConfigLocation
import com.teachermovies.bridge.logs.TvLogFiles
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * The systemd `--user` unit that keeps `teachermovies-bridge run` up (#278, ADR-0005 §1): where the
 * unit file lives, what goes in it, and the text [render] makes of [UnitSpec].
 *
 * The unit is rendered from [TEMPLATE_RESOURCE], which travels inside the jar so that an installed
 * distribution can write its own unit. Three placeholders stand for the three things only this
 * laptop knows: the absolute path of the start script ([UnitSpec.execStart]), the working directory
 * and the `Environment=` lines. Everything else -- `Restart=`, the start limit, `WantedBy=` -- is
 * the template's own text, so the runbook and the unit a human reads agree.
 *
 * Nothing secret is ever rendered into it. The token stays in the bridge's own `0600` config file
 * and the OpenSubtitles credentials in its own `0600` env file, both of which the bridge reads
 * itself; a unit file is world-readable and a service's environment is readable in
 * `/proc/<pid>/environ`, so the only names an `Environment=` line may carry are [ENVIRONMENT_KEYS],
 * each holding an absolute path. `ServiceUnitTest` asserts both halves of that.
 */
internal object ServiceUnit {
    /** The unit file's name, and the unit every `systemctl --user` command in the runbook names. */
    const val UNIT_NAME = "teachermovies-bridge.service"

    /** [UNIT_NAME] without its suffix: what `journalctl --user -u` takes. */
    const val SERVICE_NAME = "teachermovies-bridge"

    /** The unit's template, on this module's classpath. */
    const val TEMPLATE_RESOURCE = "teachermovies-bridge.service.tmpl"

    /** The one environment variable a unit always needs, and the one a user manager gets wrong. */
    const val PATH_VARIABLE = "PATH"

    /** The subdirectory of the config home the unit's TV log mirror writes into. */
    const val TV_LOGS_DIR_NAME = "tv-logs"

    /** The only `Environment=` names the unit may carry, in the order [render] writes them. */
    val ENVIRONMENT_KEYS: List<String> = listOf(PATH_VARIABLE, TvLogFiles.DIR_VARIABLE, ClaudeBinary.VARIABLE)

    /** The template's placeholders as they appear in it; a test holds the template to this list. */
    val PLACEHOLDERS: List<String> = listOf(EXEC_START, WORKING_DIRECTORY, ENVIRONMENT).map(::token)

    /**
     * Characters no rendered value may hold: `"` would close the quoted value or the quoted
     * `ExecStart` path, `$` and `\` are systemd's own expansion and escape characters on a command
     * line, and a line break would forge a directive of its own. A value carrying one is refused
     * rather than escaped, because such a path is a mistake worth reporting.
     */
    private val FORBIDDEN_CHARACTERS = setOf('"', '\\', '$', '\n', '\r')

    /** A systemd specifier introducer: `%h` in a path would become the home directory. */
    private const val SPECIFIER = "%"

    private const val EXEC_START = "EXEC_START"
    private const val WORKING_DIRECTORY = "WORKING_DIRECTORY"
    private const val ENVIRONMENT = "ENVIRONMENT"
    private const val PLACEHOLDER_PREFIX = "__"
    private const val SYSTEMD_DIR = "systemd"
    private const val USER_DIR = "user"
    private const val BIN_DIR = "bin"
    private const val PATH_SEPARATOR = ":"

    /** The unit settings [UnitRender.Refused] names, spelled as systemd spells them. */
    private const val EXEC_START_FIELD = "ExecStart"
    private const val WORKING_DIRECTORY_FIELD = "WorkingDirectory"

    /** The directory systemd's user manager reads units from: `~/.config/systemd/user`. */
    fun unitDir(
        home: Path,
        env: (String) -> String?,
    ): Path = ConfigLocation.configHome(home, env).resolve(SYSTEMD_DIR).resolve(USER_DIR)

    /** The unit file [ServiceInstaller] writes: [UNIT_NAME] inside [unitDir]. */
    fun unitFile(
        home: Path,
        env: (String) -> String?,
    ): Path = unitDir(home, env).resolve(UNIT_NAME)

    /**
     * Where the service's TV log mirror writes: `$TEACHERMOVIES_TV_LOGS_DIR` when the installing
     * shell already names one, else [TV_LOGS_DIR_NAME] inside the bridge's config directory. Never
     * [TvLogFiles.defaultDir], whose working-directory default a service's cwd would decide.
     */
    fun tvLogsDir(
        home: Path,
        env: (String) -> String?,
    ): Path {
        val explicit = env(TvLogFiles.DIR_VARIABLE)?.takeIf { it.isNotBlank() }
        if (explicit != null) return ConfigLocation.expand(explicit, home)
        return ConfigLocation.configDir(home, env).resolve(TV_LOGS_DIR_NAME)
    }

    /**
     * The unit's `PATH`: [javaHome]'s `bin` (so the start script finds the same `java` that runs
     * now), the directories of the start script and of the Claude Code CLI, then the installing
     * shell's own `PATH` -- duplicates dropped, order kept. A systemd user manager starts with a
     * minimal `PATH` and, with linger, with no desktop session to inherit one from, so this is what
     * lets the bridge and the CLI it launches find their own binaries.
     */
    fun pathValue(
        execStart: Path,
        claudeBin: Path?,
        javaHome: Path,
        env: (String) -> String?,
    ): String {
        val entries = linkedSetOf(javaHome.resolve(BIN_DIR).toString())
        execStart.parent?.let { entries.add(it.toString()) }
        claudeBin?.parent?.let { entries.add(it.toString()) }
        val inherited = env(PATH_VARIABLE).orEmpty().split(PATH_SEPARATOR)
        val usable = inherited.filter { it.isNotBlank() }
        usable.forEach { entries.add(it) }
        return entries.joinToString(PATH_SEPARATOR)
    }

    /** The unit text for [spec], or the value that cannot go into a unit and why. */
    fun render(
        spec: UnitSpec,
        template: String = readTemplate(),
    ): UnitRender {
        val refused = refused(spec)
        if (refused != null) return refused
        val text =
            template
                .replace(token(EXEC_START), value(spec.execStart.toString()))
                .replace(token(WORKING_DIRECTORY), value(spec.workingDirectory.toString()))
                .replace(token(ENVIRONMENT), environmentLines(spec).joinToString("\n"))
        return UnitRender.Rendered(text)
    }

    /** The template's own text; a missing resource is a packaging fault, not a user's mistake. */
    fun readTemplate(): String {
        val stream =
            ServiceUnit::class.java.getResourceAsStream("/$TEMPLATE_RESOURCE")
                ?: throw IllegalStateException("la plantilla de la unidad no está en el jar: $TEMPLATE_RESOURCE")
        return stream.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
    }

    private fun environmentLines(spec: UnitSpec): List<String> {
        val values =
            linkedMapOf(
                PATH_VARIABLE to spec.path,
                TvLogFiles.DIR_VARIABLE to spec.tvLogsDir.toString(),
            )
        if (spec.claudeBin != null) values[ClaudeBinary.VARIABLE] = spec.claudeBin.toString()
        return values.entries.map { entry -> "Environment=\"${entry.key}=${value(entry.value)}\"" }
    }

    /** The first value systemd would misread, as the refusal [render] returns. */
    private fun refused(spec: UnitSpec): UnitRender.Refused? {
        val values =
            linkedMapOf(
                EXEC_START_FIELD to spec.execStart.toString(),
                WORKING_DIRECTORY_FIELD to spec.workingDirectory.toString(),
                TvLogFiles.DIR_VARIABLE to spec.tvLogsDir.toString(),
                PATH_VARIABLE to spec.path,
            )
        spec.claudeBin?.let { values[ClaudeBinary.VARIABLE] = it.toString() }
        for ((name, text) in values) {
            val character = text.firstOrNull { FORBIDDEN_CHARACTERS.contains(it) } ?: continue
            return UnitRender.Refused(name, character)
        }
        return null
    }

    /** [text] with systemd's specifier introducer doubled, so a path keeps its own `%`. */
    private fun value(text: String): String = text.replace(SPECIFIER, "$SPECIFIER$SPECIFIER")

    private fun token(name: String): String = "$PLACEHOLDER_PREFIX$name$PLACEHOLDER_PREFIX"
}

/**
 * Everything only this laptop knows about the unit (#278). Every path is absolute and normalized
 * before it gets here, as [ConfigLocation] guarantees; [claudeBin] null means the Claude Code CLI
 * was not found, and the unit simply carries no `CLAUDE_BIN`.
 */
internal data class UnitSpec(
    val execStart: Path,
    val workingDirectory: Path,
    val path: String,
    val tvLogsDir: Path,
    val claudeBin: Path?,
)

/** What [ServiceUnit.render] made of a [UnitSpec]. */
internal sealed interface UnitRender {
    /** The unit's text, ready for [ServiceInstaller]. */
    data class Rendered(
        val text: String,
    ) : UnitRender

    /**
     * A value systemd would misread, so nothing was rendered. [field] names the unit setting it
     * belongs to and [character] is the offending one -- never the value itself, which is a path
     * this laptop's own filesystem handed over.
     */
    data class Refused(
        val field: String,
        val character: Char,
    ) : UnitRender
}
