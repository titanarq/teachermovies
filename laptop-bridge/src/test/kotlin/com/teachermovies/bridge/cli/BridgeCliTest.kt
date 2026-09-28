package com.teachermovies.bridge.cli

import com.teachermovies.bridge.claude.ClaudeDirs
import com.teachermovies.bridge.claude.FakeClaude
import com.teachermovies.bridge.config.BridgeConfig
import com.teachermovies.bridge.config.BridgeConfigStore
import com.teachermovies.bridge.config.ConfigLocation
import com.teachermovies.bridge.opensubtitles.CredentialsFile
import com.teachermovies.bridge.protocol.LogsPageDto
import com.teachermovies.bridge.service.ServiceUnit
import com.teachermovies.bridge.tv.FakeTv
import com.teachermovies.bridge.tv.jsonField
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * The whole CLI against an in-test Ktor server standing in for the TV (#271): `pair` stores what the
 * TV issued, `doctor` reports this bridge's health, `logs` prints the TV's lines, `unpair` forgets
 * everything -- and at no point does the token, or the PIN, reach a stream a human reads.
 *
 * The home directory is a [TemporaryFolder] and the environment is empty, so no test reads or writes
 * the real `~/.config`, and no host's `XDG_CONFIG_HOME` can change what a test expects.
 */
class BridgeCliTest {
    @get:Rule val tmpFolder = TemporaryFolder()

    private val pin = "pin-secreto-9f3a"
    private val tv = FakeTv().also { it.pin = pin }
    private val out = StringBuilder()
    private val err = StringBuilder()

    private val home: Path
        get() = tmpFolder.root.toPath()

    private val configPath: Path
        get() = ConfigLocation.configFile(null, home) { null }

    /** Where `doctor` looks for the OpenSubtitles credentials (#281) under this test's home. */
    private val credentialsPath: Path
        get() = CredentialsFile.defaultPath(home) { null }

    private val osPassword = "contraseña-opensubtitles-7c1e"
    private val osApiKey = "clave-api-opensubtitles-55aa"

    /** Every test starts with a well-kept credentials file, so only the tests about it see it fail. */
    @Before
    fun writeCredentials() {
        Files.createDirectories(requireNotNull(credentialsPath.parent))
        Files.writeString(
            credentialsPath,
            "OPENSUBTITLES_API_KEY=$osApiKey\nOPENSUBTITLES_USERNAME=usuario-os\nOPENSUBTITLES_PASSWORD=$osPassword\n",
        )
        Files.setPosixFilePermissions(credentialsPath, PosixFilePermissions.fromString("rw-------"))
    }

    /** A fake `claude` (#276) where `doctor` finds one with no PATH: `~/.local/bin/claude`. */
    private val fakeClaude by lazy { FakeClaude(tmpFolder.root.toPath().resolve("fake-claude")) }

    private val claudePath: Path
        get() = home.resolve(".local/bin/claude")

    @Before
    fun installClaude() {
        fakeClaude.installAt(claudePath)
    }

    @After
    fun tearDown() {
        tv.close()
    }

    private fun run(vararg args: String): Int = BridgeCli(out, err, home = home, env = { null }).run(arrayOf(*args))

    private fun pair(): Int = run("pair", "--url", tv.baseUrl, "--pin", pin, "--name", "salon")

    /** The token and the PIN, plus anything else named, appear in neither stream. */
    private fun assertNoSecrets(vararg alsoAbsent: String) {
        val printed = out.toString() + err.toString()

        assertFalse(printed.contains(tv.token))
        assertFalse(printed.contains(pin))
        alsoAbsent.forEach { assertFalse(printed.contains(it)) }
    }

    private fun closedPort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    // --- pair ---

    @Test
    fun `pair stores the token with mode 0600 and prints neither the token nor the PIN`() {
        assertEquals(0, pair())

        val store = BridgeConfigStore(configPath)
        val written = Files.readString(store.path)
        assertEquals(tv.token, written.jsonField("token"))
        assertEquals(tv.baseUrl, written.jsonField("tvUrl"))
        assertEquals("salon", written.jsonField("deviceName"))
        assertEquals("0600", store.permissions())
        assertTrue(out.toString().contains("Emparejado con ${tv.baseUrl}"))
        assertNoSecrets()
    }

    @Test
    fun `pair with a wrong PIN stores nothing and fails`() {
        assertEquals(1, run("pair", "--url", tv.baseUrl, "--pin", "otro-pin"))

        assertFalse(Files.exists(configPath))
        assertTrue(err.toString().contains("PIN incorrecto"))
        assertNoSecrets()
    }

    @Test
    fun `pair refuses a TV that issues phone-scoped tokens`() {
        tv.issuedScope = "phone"

        assertEquals(1, pair())

        assertFalse(Files.exists(configPath))
        assertTrue(err.toString().contains("phone"))
        assertTrue(err.toString().contains("No se ha guardado nada"))
        assertNoSecrets()
    }

    @Test
    fun `pair against a TV that is not there fails with a message, not a stack trace`() {
        assertEquals(1, run("pair", "--url", "http://127.0.0.1:${closedPort()}", "--pin", pin))

        assertFalse(Files.exists(configPath))
        assertTrue(err.toString().contains("no se ha podido hablar con la TV"))
        assertNoSecrets()
    }

    // --- doctor ---

    @Test
    fun `doctor is healthy once paired`() {
        pair()
        out.setLength(0)

        assertEquals(0, run("doctor"))

        val report = out.toString()
        assertTrue(report.contains("configuración: $configPath"))
        assertTrue(report.contains("permisos: 0600"))
        assertTrue(report.contains("emparejado: ${tv.baseUrl}"))
        assertTrue(report.contains("versión 1.0"))
        assertTrue(report.contains("el token es válido"))
        assertTrue(report.contains("OK    credenciales OpenSubtitles: $credentialsPath (permisos 0600"))
        assertTrue(report.contains("OK    Claude Code: $claudePath"))
        assertTrue(report.contains("OK    sesión de Claude Code: iniciada (claude.ai, plan max)"))
        assertTrue(
            report.contains(
                "OK    ajustes de Claude: modelo sonnet, esfuerzo low, tope 300 trabajos/día (predeterminados",
            ),
        )
        assertTrue(report.contains("doctor: todo correcto"))
        assertNoSecrets(osApiKey, osPassword, "usuario-os")
    }

    @Test
    fun `doctor reports a Claude Code CLI that is not logged in and how to log in`() {
        pair()
        fakeClaude.authAnswer("""{"loggedIn":false}""", exitCode = 1)
        out.setLength(0)

        assertEquals(1, run("doctor"))

        val report = out.toString()
        assertTrue(report.contains("FALLO sesión de Claude Code: no hay sesión iniciada"))
        assertTrue(report.contains("-> inicia sesión con: $claudePath auth login"))
        assertTrue(report.contains("doctor: 1 problema"))
    }

    @Test
    fun `doctor reports a missing Claude Code CLI and where it looked`() {
        pair()
        Files.delete(claudePath)
        out.setLength(0)

        assertEquals(1, run("doctor"))

        val report = out.toString()
        assertTrue(report.contains("FALLO Claude Code: no se encuentra 'claude' en el PATH ni en ~/.local/bin"))
        assertTrue(report.contains("CLAUDE_BIN"))
        assertFalse(report.contains("sesión de Claude Code"))
        assertTrue(report.contains("doctor: 1 problema"))
    }

    @Test
    fun `doctor reports a CLAUDE_BIN that does not exist rather than looking elsewhere`() {
        pair()
        out.setLength(0)
        val wrong = home.resolve("no-existe/claude")

        val env = { name: String -> if (name == "CLAUDE_BIN") wrong.toString() else null }
        assertEquals(1, BridgeCli(out, err, home = home, env = env).run(arrayOf("doctor")))

        assertTrue(out.toString().contains("FALLO Claude Code: CLAUDE_BIN=$wrong no existe"))
    }

    @Test
    fun `doctor reports an unusable claude json next to the config`() {
        pair()
        Files.writeString(configPath.resolveSibling("claude.json"), """{"turnTimeoutSeconds":0}""")
        out.setLength(0)

        assertEquals(1, run("doctor"))

        assertTrue(
            out.toString().contains(
                "FALLO ajustes de Claude: ${configPath.resolveSibling("claude.json")} no se puede usar",
            ),
        )
        assertTrue(out.toString().contains("doctor: 1 problema"))
    }

    @Test
    fun `doctor shows the stderr tail the last dead Claude process left, as information`() {
        pair()
        val tail = ClaudeDirs.default(home) { null }.stderrTail("translate")
        Files.createDirectories(requireNotNull(tail.parent))
        Files.writeString(tail, "# proceso 42, código 1\nError: system prompt file not found: /x\n")
        out.setLength(0)

        assertEquals(0, run("doctor"))

        assertTrue(out.toString().contains("INFO  último stderr de Claude Code ($tail):"))
        assertTrue(out.toString().contains("Error: system prompt file not found: /x"))
    }

    @Test
    fun `doctor reports missing OpenSubtitles credentials and what the file needs`() {
        pair()
        Files.delete(credentialsPath)
        out.setLength(0)

        assertEquals(1, run("doctor"))

        val report = out.toString()
        assertTrue(report.contains("FALLO credenciales OpenSubtitles: no existe $credentialsPath"))
        assertTrue(report.contains("OPENSUBTITLES_API_KEY"))
        assertTrue(report.contains("doctor: 1 problema"))
    }

    @Test
    fun `doctor reports loose credentials permissions without printing the file`() {
        pair()
        Files.setPosixFilePermissions(credentialsPath, PosixFilePermissions.fromString("rw-r--r--"))
        out.setLength(0)

        assertEquals(1, run("doctor"))

        val report = out.toString()
        assertTrue(report.contains("FALLO credenciales OpenSubtitles: 0644 en $credentialsPath"))
        assertTrue(report.contains("chmod 0600 $credentialsPath"))
        assertNoSecrets(osApiKey, osPassword, "usuario-os")
    }

    @Test
    fun `doctor checks the credentials even without a config, and never opens them`() {
        // Unreadable to its owner too: doctor must still report it by existence and mode alone.
        Files.setPosixFilePermissions(credentialsPath, PosixFilePermissions.fromString("-w-------"))

        assertEquals(1, run("doctor"))

        val report = out.toString()
        assertTrue(report.contains("no existe $configPath"))
        assertTrue(report.contains("credenciales OpenSubtitles: 0200 en $credentialsPath"))
        assertTrue(report.contains("doctor: 2 problemas"))
    }

    @Test
    fun `the credentials path can be moved with an environment variable`() {
        val elsewhere = home.resolve("otra/opensubtitles.env")
        Files.createDirectories(requireNotNull(elsewhere.parent))
        Files.move(credentialsPath, elsewhere)
        pair()
        out.setLength(0)

        val env = { name: String -> if (name == CredentialsFile.PATH_VARIABLE) elsewhere.toString() else null }
        assertEquals(0, BridgeCli(out, err, home = home, env = env).run(arrayOf("doctor")))

        assertTrue(out.toString().contains("credenciales OpenSubtitles: $elsewhere"))
    }

    @Test
    fun `doctor reports a missing config and how to pair`() {
        assertEquals(1, run("doctor"))

        val report = out.toString()
        assertTrue(report.contains("no existe $configPath"))
        assertTrue(report.contains("teachermovies-bridge pair --url"))
        assertTrue(report.contains("doctor: 1 problema"))
    }

    @Test
    fun `doctor reports a corrupt config and how to pair`() {
        Files.createDirectories(requireNotNull(configPath.parent))
        Files.writeString(configPath, "esto no es json")

        assertEquals(1, run("doctor"))

        assertTrue(out.toString().contains("no se puede leer"))
        assertNoSecrets("esto no es json")
    }

    @Test
    fun `doctor reports a token the TV no longer accepts`() {
        val issued = tv.token
        pair()
        tv.token = "otro-token-distinto"
        out.setLength(0)

        assertEquals(1, run("doctor"))

        val report = out.toString()
        assertTrue(report.contains("OK    TV /api/status"))
        assertTrue(report.contains("FALLO TV /api/logs"))
        assertTrue(report.contains("ha rechazado el token (401)"))
        assertTrue(report.contains("doctor: 1 problema"))
        assertNoSecrets(issued)
    }

    @Test
    fun `doctor reports permissions that are not 0600 and how to fix them`() {
        pair()
        Files.setPosixFilePermissions(configPath, PosixFilePermissions.fromString("rw-r--r--"))
        out.setLength(0)

        assertEquals(1, run("doctor"))

        val report = out.toString()
        assertTrue(report.contains("permisos: 0644"))
        assertTrue(report.contains("chmod 0600 $configPath"))
    }

    @Test
    fun `doctor reports a TV that does not answer, without printing the token it would have used`() {
        val forgottenToken = "tok_guardado_pero_invisible"
        BridgeConfigStore(configPath).save(
            BridgeConfig(tvUrl = "http://127.0.0.1:${closedPort()}", token = forgottenToken),
        )

        assertEquals(1, run("doctor"))

        val report = out.toString()
        assertTrue(report.contains("no se ha podido hablar con la TV"))
        assertTrue(report.contains("doctor: 2 problemas"))
        assertNoSecrets(forgottenToken)
    }

    // --- logs ---

    @Test
    fun `logs prints the TV's lines and the cursor to continue from`() {
        pair()
        out.setLength(0)

        assertEquals(0, run("logs"))

        val printed = out.toString()
        assertTrue(printed.contains("descarga completada"))
        assertTrue(printed.contains("INFO"))
        assertTrue(printed.contains("[torrent]"))
        assertTrue(printed.contains("WARN"))
        assertTrue(printed.contains("underrun de 240 ms"))
        assertTrue(printed.contains("2 líneas"))
        assertTrue(printed.contains("--since 8"))
        assertNoSecrets()
    }

    @Test
    fun `logs sends its filters and the stored token in the header`() {
        pair()

        assertEquals(0, run("logs", "--since", "7", "--level", "warn", "--limit", "2"))

        val request = tv.received.last()
        assertEquals("since=7&level=warn&limit=2", request.query)
        assertEquals("Bearer ${tv.token}", request.authorization)
        assertNoSecrets()
    }

    @Test
    fun `logs without a pairing says how to get one`() {
        assertEquals(1, run("logs"))

        assertTrue(err.toString().contains("Empareja con"))
    }

    @Test
    fun `logs of a TV with nothing to show says so`() {
        pair()
        tv.logsPage = LogsPageDto(bootId = "boot-sin-lineas", entries = emptyList())
        out.setLength(0)

        assertEquals(0, run("logs"))

        assertTrue(out.toString().contains("no tiene líneas"))
    }

    // --- unpair ---

    @Test
    fun `run without a pairing fails at once and says how to pair (#277)`() {
        assertEquals(1, run("run"))
        assertTrue(err.toString().contains("no existe $configPath"))
        assertTrue(err.toString().contains("teachermovies-bridge pair"))
    }

    @Test
    fun `run stops with a failure when the TV no longer accepts the token (#277)`() {
        pair()
        val storedToken = tv.token
        tv.token = "token-renovado"

        assertEquals(1, run("run"))

        assertTrue(out.toString().contains("La TV ha rechazado el token (401)"))
        assertTrue(err.toString().contains("La TV ya no acepta el token de este portátil."))
        assertTrue(Files.exists(configPath.resolveSibling("bridge.log")))
        assertNoSecrets(storedToken)
    }

    @Test
    fun `unpair deletes the config, and doctor then reports no pairing`() {
        pair()

        assertEquals(0, run("unpair"))

        assertFalse(Files.exists(configPath))
        assertTrue(out.toString().contains("Desemparejado"))
        assertTrue(out.toString().contains("La TV conserva su copia del token"))

        out.setLength(0)
        assertEquals(1, run("doctor"))
        assertNoSecrets()
    }

    @Test
    fun `unpair with nothing stored is not an error`() {
        assertEquals(0, run("unpair"))

        assertTrue(out.toString().contains("No había ningún emparejamiento"))
    }

    @Test
    fun `unpair deletes a config it cannot read, and says why it could not`() {
        Files.createDirectories(requireNotNull(configPath.parent))
        Files.writeString(configPath, "esto no es json")

        assertEquals(0, run("unpair"))

        assertFalse(Files.exists(configPath))
        assertTrue(out.toString().contains("no era legible"))
        assertNoSecrets("esto no es json")
    }

    // --- install-service (#278) ---

    /** A start script for the unit to launch: this test's environment derives none of its own. */
    private fun startScript(): Path {
        val path = home.resolve("bridge/bin/teachermovies-bridge")
        Files.createDirectories(requireNotNull(path.parent))
        Files.writeString(path, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
        return path
    }

    /** Where the CLI writes the unit with this test's empty environment. */
    private fun unitFile(): Path = ServiceUnit.unitFile(home) { null }

    private fun installService(): Int = run("install-service", "--exec", startScript().toString())

    @Test
    fun `install-service writes the unit and says how to put it to work`() {
        val script = startScript()

        assertEquals(0, run("install-service", "--exec", script.toString()))

        val unit = Files.readString(unitFile())
        assertTrue(unit.contains("ExecStart=\"${script}\" run"))
        assertTrue(unit.contains("Restart=on-failure"))
        assertTrue(unit.contains("WantedBy=default.target"))
        assertTrue(out.toString().contains("systemctl --user daemon-reload"))
        assertTrue(out.toString().contains("systemctl --user enable --now teachermovies-bridge.service"))
        assertTrue(out.toString().contains("loginctl enable-linger"))
        assertTrue(out.toString().contains("journalctl --user -u teachermovies-bridge"))
        assertNoSecrets()
    }

    @Test
    fun `install-service leaves the token, the PIN and the credentials out of the unit and of the report`() {
        assertEquals(0, pair())

        assertEquals(0, installService())

        val unit = Files.readString(unitFile())
        assertFalse(unit.contains(tv.token))
        assertFalse(unit.contains(pin))
        assertFalse(unit.contains(osApiKey))
        assertFalse(unit.contains(osPassword))
        assertFalse(unit.contains(Files.readString(configPath)))
        // The report names the file the token stays in, and nothing else about it.
        assertTrue(out.toString().contains(configPath.toString()))
        assertNoSecrets(osApiKey, osPassword)
    }

    @Test
    fun `install-service says when there is no pairing for the service to run with`() {
        assertEquals(0, installService())
        assertTrue(out.toString().contains("no hay emparejamiento"))

        out.setLength(0)
        assertEquals(0, pair())
        assertEquals(0, installService())

        assertFalse(out.toString().contains("no hay emparejamiento"))
    }

    @Test
    fun `a second install-service replaces the unit instead of leaving two`() {
        assertEquals(0, installService())
        out.setLength(0)

        assertEquals(0, installService())

        assertTrue(out.toString().contains("sustituida"))
        assertTrue(Files.readString(unitFile()).contains("ExecStart="))
    }

    @Test
    fun `install-service with no --exec to derive from asks for one and writes no unit`() {
        assertEquals(1, run("install-service"))

        assertTrue(err.toString().contains("--exec"))
        assertFalse(Files.exists(unitFile()))
        assertEquals("", out.toString())
    }

    @Test
    fun `an --exec that is not a runnable file fails without writing a unit`() {
        assertEquals(1, run("install-service", "--exec", home.resolve("nada/bin/teachermovies-bridge").toString()))

        assertTrue(err.toString().contains("no existe"))
        assertFalse(Files.exists(unitFile()))
        assertNoSecrets()
    }

    // --- the command line itself ---

    @Test
    fun `--help prints the usage to stdout and succeeds`() {
        assertEquals(0, run("--help"))

        assertTrue(out.toString().contains("teachermovies-bridge"))
        assertTrue(out.toString().contains("Códigos de salida"))
        assertEquals("", err.toString())
    }

    @Test
    fun `a command line that means nothing prints the usage to stderr and exits 2`() {
        assertEquals(2, run("pair", "--pin", pin))

        assertTrue(err.toString().contains("'--url' es obligatoria"))
        assertTrue(err.toString().contains("Códigos de salida"))
        assertEquals("", out.toString())
        assertFalse(Files.exists(configPath))
        assertNoSecrets()
    }

    @Test
    fun `no arguments at all is a usage error`() {
        assertEquals(2, run())

        assertTrue(err.toString().contains("Códigos de salida"))
        assertEquals("", out.toString())
    }

    @Test
    fun `--config points every subcommand at another file`() {
        val other = home.resolve("otro-config.json")

        assertEquals(0, run("--config", other.toString(), "pair", "--url", tv.baseUrl, "--pin", pin))

        assertTrue(Files.exists(other))
        assertFalse(Files.exists(configPath))
        assertEquals("0600", BridgeConfigStore(other).permissions())
        assertEquals(0, run("doctor", "--config", other.toString()))
        assertNoSecrets()
    }

    @Test
    fun `the whole lifecycle prints no token and no PIN`() {
        assertEquals(0, pair())
        assertEquals(0, run("doctor"))
        assertEquals(0, run("logs"))
        assertEquals(0, run("unpair"))

        assertNoSecrets()
    }
}
