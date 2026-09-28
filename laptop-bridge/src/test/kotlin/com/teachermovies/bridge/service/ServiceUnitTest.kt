package com.teachermovies.bridge.service

import com.teachermovies.bridge.config.BridgeConfig
import com.teachermovies.bridge.config.BridgeConfigStore
import com.teachermovies.bridge.config.ConfigLocation
import com.teachermovies.bridge.config.ConfigSave
import com.teachermovies.bridge.logs.TvLogFiles
import com.teachermovies.bridge.opensubtitles.CredentialsFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The systemd `--user` unit's own text (#278): that it launches an absolute path, that it restarts a
 * `run` which fails, that its `Environment=` lines are the three names it is allowed to carry -- and
 * above all that no token, PIN or OpenSubtitles credential is ever embedded in it, however well
 * paired and credentialed the laptop it was rendered on happens to be.
 *
 * The paths below are a home no test writes to; the one test that installs a unit gets a temporary
 * home of its own.
 */
class ServiceUnitTest {
    @get:Rule val tmpFolder = TemporaryFolder()

    private val home = Paths.get("/home/alumno")
    private val execStart = Paths.get("/opt/bridge/bin/teachermovies-bridge")
    private val javaHome = Paths.get("/usr/lib/jvm/java-17-openjdk-amd64")
    private val claudeBin = Paths.get("/home/alumno/.local/bin/claude")
    private val tvLogsDir = Paths.get("/home/alumno/.config/teachermovies-bridge/tv-logs")
    private val specPath = "$javaHome/bin:/opt/bridge/bin:/home/alumno/.local/bin:/usr/bin:/bin"

    private fun spec(
        exec: Path = execStart,
        working: Path = home,
        claude: Path? = claudeBin,
        path: String = specPath,
        logs: Path = tvLogsDir,
    ) = UnitSpec(
        execStart = exec,
        workingDirectory = working,
        path = path,
        tvLogsDir = logs,
        claudeBin = claude,
    )

    private fun rendered(spec: UnitSpec = spec()): String {
        val render = ServiceUnit.render(spec)
        assertTrue("the unit did not render: $render", render is UnitRender.Rendered)
        return (render as UnitRender.Rendered).text
    }

    /** The lines of [text] that assign an environment variable. */
    private fun environmentLines(text: String): List<String> = text.lines().filter { it.startsWith("Environment=") }

    /** The variable name an `Environment=` line assigns. */
    private fun environmentKey(line: String): String = line.removePrefix("Environment=\"").substringBefore('=')

    /** The executable `ExecStart=` names: the first word of the line, unquoted. */
    private fun execStartOf(text: String): String {
        val line = text.lines().single { it.startsWith("ExecStart=") }
        return line.removePrefix("ExecStart=").substringBefore(' ').trim('"')
    }

    // --- the unit's text ---

    @Test
    fun `the unit launches the start script by an absolute path, with run as its argument`() {
        val text = rendered()

        assertEquals("/opt/bridge/bin/teachermovies-bridge", execStartOf(text))
        assertTrue(Paths.get(execStartOf(text)).isAbsolute)
        assertTrue(text.contains("ExecStart=\"/opt/bridge/bin/teachermovies-bridge\" run"))
        assertTrue(text.contains("WorkingDirectory=/home/alumno"))
    }

    @Test
    fun `the unit restarts a run that fails, and is wanted by the user manager's default target`() {
        val text = rendered()

        assertTrue(text.contains("Restart=on-failure"))
        assertTrue(text.contains("RestartSec=10"))
        assertTrue(text.contains("StartLimitIntervalSec=300"))
        assertTrue(text.contains("StartLimitBurst=5"))
        assertTrue(text.contains("Type=exec"))
        assertTrue(text.contains("WantedBy=default.target"))
    }

    @Test
    fun `the environment carries exactly the three names the unit is allowed to carry`() {
        val keys = environmentLines(rendered()).map { environmentKey(it) }

        assertEquals(ServiceUnit.ENVIRONMENT_KEYS, keys)
    }

    @Test
    fun `every environment value is the absolute path it was given`() {
        val expected =
            listOf(
                "Environment=\"PATH=$specPath\"",
                "Environment=\"${TvLogFiles.DIR_VARIABLE}=/home/alumno/.config/teachermovies-bridge/tv-logs\"",
                "Environment=\"${ClaudeBinary.VARIABLE}=/home/alumno/.local/bin/claude\"",
            )

        assertEquals(expected, environmentLines(rendered()))
    }

    @Test
    fun `a laptop with no Claude CLI gets a unit without CLAUDE_BIN`() {
        val text = rendered(spec(claude = null))
        val keys = environmentLines(text).map { environmentKey(it) }

        // The template's own prose names CLAUDE_BIN to state the invariant; what must not appear is
        // a directive that sets it.
        assertFalse(text.contains("Environment=\"${ClaudeBinary.VARIABLE}="))
        assertEquals(listOf(ServiceUnit.PATH_VARIABLE, TvLogFiles.DIR_VARIABLE), keys)
    }

    @Test
    fun `no placeholder of the template survives into the unit`() {
        assertFalse(rendered().contains("__"))
    }

    @Test
    fun `a percent sign in a path is doubled so systemd reads it as itself, not as a specifier`() {
        val text = rendered(spec(working = Paths.get("/home/100%d/alumno")))

        assertTrue(text.contains("WorkingDirectory=/home/100%%d/alumno"))
    }

    @Test
    fun `a value systemd would misread is refused instead of rendered`() {
        val quote = ServiceUnit.render(spec(exec = Paths.get("/opt/bri\"dge/bin/teachermovies-bridge")))
        val dollar = ServiceUnit.render(spec(exec = Paths.get("/opt/\$HOME/bin/teachermovies-bridge")))
        val backslash = ServiceUnit.render(spec(claude = Paths.get("/home/alumno\\bin\\claude")))

        assertEquals(UnitRender.Refused("ExecStart", '"'), quote)
        assertEquals(UnitRender.Refused("ExecStart", '$'), dollar)
        assertEquals(UnitRender.Refused(ClaudeBinary.VARIABLE, '\\'), backslash)
    }

    // --- the template ---

    @Test
    fun `the template holds only the placeholders the renderer substitutes`() {
        val template = ServiceUnit.readTemplate()
        val found = PLACEHOLDER.findAll(template).map { it.value }

        assertEquals(ServiceUnit.PLACEHOLDERS.toSet(), found.toSet())
    }

    @Test
    fun `the template names no directive that could carry a secret into the service`() {
        val template = ServiceUnit.readTemplate()

        SECRET_SPELLINGS.forEach { assertFalse("the template mentions $it", template.contains(it)) }
    }

    // --- where the unit and its paths go ---

    @Test
    fun `the unit goes where systemd's user manager reads units from, and XDG_CONFIG_HOME moves it`() {
        val xdg = { name: String -> if (name == ConfigLocation.CONFIG_HOME_VARIABLE) "/datos/config" else null }
        val moved = Paths.get("/datos/config/systemd/user/teachermovies-bridge.service")

        assertEquals(
            Paths.get("/home/alumno/.config/systemd/user/teachermovies-bridge.service"),
            ServiceUnit.unitFile(home) { null },
        )
        assertEquals(moved, ServiceUnit.unitFile(home, xdg))
    }

    @Test
    fun `the TV log directory is inside the config directory unless the environment names one`() {
        val explicit = { name: String -> if (name == TvLogFiles.DIR_VARIABLE) "~/registros-tv" else null }

        assertEquals(
            Paths.get("/home/alumno/.config/teachermovies-bridge/tv-logs"),
            ServiceUnit.tvLogsDir(home) { null },
        )
        assertEquals(Paths.get("/home/alumno/registros-tv"), ServiceUnit.tvLogsDir(home, explicit))
    }

    @Test
    fun `the unit's PATH puts java, the script and the CLI ahead of the shell's own PATH`() {
        val shell = { name: String -> if (name == ServiceUnit.PATH_VARIABLE) "/usr/bin:/bin:/usr/bin:" else null }

        val path = ServiceUnit.pathValue(execStart, claudeBin, javaHome, shell)

        assertEquals("$javaHome/bin:/opt/bridge/bin:/home/alumno/.local/bin:/usr/bin:/bin", path)
    }

    @Test
    fun `the unit's PATH carries java and the script even with no PATH to inherit and no CLI`() {
        val path = ServiceUnit.pathValue(execStart, null, javaHome) { null }

        assertEquals("$javaHome/bin:/opt/bridge/bin", path)
    }

    // --- no secret in the unit ---

    @Test
    fun `no token, PIN or OpenSubtitles credential is ever embedded in the unit file`() {
        val pairedHome = tmpFolder.root.toPath()
        val env = { _: String -> null }
        val token = "tok-9f3a7c2e1b8d4460"
        val pin = "482916"
        val apiKey = "clave-api-opensubtitles-55aa"
        val username = "usuario-opensubtitles-1d02"
        val password = "contraseña-opensubtitles-7c1e"
        pair(pairedHome, env, token)
        writeCredentials(pairedHome, env, apiKey, username, password)

        val unitFile = ServiceUnit.unitFile(pairedHome, env)
        val installed = ServiceInstaller(unitFile).install(rendered(specFor(pairedHome)))

        assertTrue(installed is ServiceInstall.Installed)
        val unit = Files.readString(unitFile)
        listOf(token, pin, apiKey, username, password).forEach { secret ->
            assertFalse("the unit embeds a secret", unit.contains(secret))
        }
        SECRET_SPELLINGS.forEach { assertFalse("the unit carries $it", unit.contains(it)) }
        // Neither does the config file's own text, which is where the token really lives.
        assertFalse(unit.contains(Files.readString(configPath(pairedHome, env))))
    }

    /** The unit a real install on [home] would write, with that home's own paths. */
    private fun specFor(home: Path): UnitSpec {
        val exec = home.resolve("bridge/bin/teachermovies-bridge")
        val env = { _: String -> null }
        return spec(
            exec = exec,
            working = home,
            claude = null,
            path = ServiceUnit.pathValue(exec, null, javaHome, env),
            logs = ServiceUnit.tvLogsDir(home, env),
        )
    }

    private fun configPath(
        home: Path,
        env: (String) -> String?,
    ): Path = ConfigLocation.configFile(null, home, env)

    /** A stored pairing, so the test renders the unit of a laptop that already has a token. */
    private fun pair(
        home: Path,
        env: (String) -> String?,
        token: String,
    ) {
        val config = BridgeConfig(tvUrl = "http://192.168.1.20:8787", token = token, deviceName = "salon")
        val saved = BridgeConfigStore(configPath(home, env)).save(config)
        assertTrue(saved is ConfigSave.Saved)
    }

    private fun writeCredentials(
        home: Path,
        env: (String) -> String?,
        apiKey: String,
        username: String,
        password: String,
    ) {
        val path = CredentialsFile.defaultPath(home, env)
        Files.createDirectories(requireNotNull(path.parent))
        val text =
            "${CredentialsFile.API_KEY}=$apiKey\n" +
                "${CredentialsFile.USERNAME}=$username\n" +
                "${CredentialsFile.PASSWORD}=$password\n"
        Files.writeString(path, text)
    }

    private companion object {
        val PLACEHOLDER = Regex("__[A-Z_]+__")

        /**
         * Everything a unit could carry a secret with: a file of environment assignments, an
         * inherited environment, an authentication header, or the spelling of one of the secrets
         * this bridge holds. The template's prose says "token" and "credentials" in lowercase, and
         * is meant to.
         */
        val SECRET_SPELLINGS =
            listOf(
                "EnvironmentFile",
                "PassEnvironment",
                "Bearer",
                "Authorization",
                "TOKEN",
                "API_KEY",
                "PASSWORD",
                "SECRET",
                "ANTHROPIC",
                "--pin",
            )
    }
}
