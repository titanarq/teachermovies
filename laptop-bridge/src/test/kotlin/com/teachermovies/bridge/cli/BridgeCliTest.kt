package com.teachermovies.bridge.cli

import com.teachermovies.bridge.config.BridgeConfig
import com.teachermovies.bridge.config.BridgeConfigStore
import com.teachermovies.bridge.config.ConfigLocation
import com.teachermovies.bridge.protocol.LogsPageDto
import com.teachermovies.bridge.tv.FakeTv
import com.teachermovies.bridge.tv.jsonField
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertTrue(report.contains("doctor: todo correcto"))
        assertNoSecrets()
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
