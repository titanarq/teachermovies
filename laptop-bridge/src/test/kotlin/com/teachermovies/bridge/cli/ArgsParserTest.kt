package com.teachermovies.bridge.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The command line of `teachermovies-bridge` (#271): what each subcommand accepts, and that a
 * mistake is a [ParseResult.UsageError] naming the offending option -- never the value typed after
 * it, because that value could be the PIN.
 */
class ArgsParserTest {
    private fun parse(vararg args: String): ParseResult = ArgsParser.parse(args.toList())

    private fun parsed(vararg args: String): ParseResult.Parsed {
        val result = parse(*args)
        assertTrue(result is ParseResult.Parsed)
        return result as ParseResult.Parsed
    }

    private fun error(vararg args: String): ParseResult.UsageError {
        val result = parse(*args)
        assertTrue(result is ParseResult.UsageError)
        return result as ParseResult.UsageError
    }

    private fun message(vararg args: String): String = requireNotNull(error(*args).message)

    // --- the subcommands ---

    @Test
    fun `pair reads its url, pin and name`() {
        val invocation = parsed("pair", "--url", "http://192.168.1.20:8787", "--pin", "482916", "--name", "salon")

        assertEquals(Command.Pair("http://192.168.1.20:8787", "482916", "salon"), invocation.command)
        assertNull(invocation.configSpec)
    }

    @Test
    fun `pair drops a trailing slash from the url and leaves the device name to the command`() {
        val command = parsed("pair", "--url", "http://192.168.1.20:8787/", "--pin", "1").command

        assertEquals(Command.Pair("http://192.168.1.20:8787", "1", null), command)
    }

    @Test
    fun `--config is read wherever it appears on the command line`() {
        assertEquals("~/otro.json", parsed("--config", "~/otro.json", "doctor").configSpec)
        assertEquals("~/otro.json", parsed("doctor", "--config", "~/otro.json").configSpec)
    }

    @Test
    fun `unpair and doctor need nothing and take nothing`() {
        assertEquals(Command.Unpair, parsed("unpair").command)
        assertEquals(Command.Doctor, parsed("doctor").command)
        assertTrue(message("doctor", "--limit", "2").contains("no admite"))
    }

    @Test
    fun `run takes no options (#277)`() {
        assertEquals(Command.Run, parsed("run").command)
        assertTrue(message("run", "--limit", "2").contains("no admite"))
    }

    @Test
    fun `logs defaults every filter, so the TV applies its own`() {
        assertEquals(Command.Logs(null, null, null), parsed("logs").command)
    }

    @Test
    fun `logs reads its filters, with the level in lower case as the TV compares it`() {
        val command = parsed("logs", "--since", "7", "--level", "WARN", "--limit", "2").command

        assertEquals(Command.Logs(7L, "warn", 2), command)
    }

    @Test
    fun `install-service takes no option but --exec (#278)`() {
        assertEquals(Command.InstallService(null), parsed("install-service").command)
        assertEquals(
            Command.InstallService("/opt/puente/bin/teachermovies-bridge"),
            parsed("install-service", "--exec", "/opt/puente/bin/teachermovies-bridge").command,
        )
        assertEquals(Command.InstallService(null), parsed("install-service", "--exec", "   ").command)
    }

    @Test
    fun `install-service reads --config like every subcommand`() {
        val invocation = parsed("--config", "~/otro.json", "install-service")

        assertEquals("~/otro.json", invocation.configSpec)
        assertEquals(Command.InstallService(null), invocation.command)
    }

    @Test
    fun `help is a result of its own`() {
        assertEquals(ParseResult.Help, parse("-h"))
        assertEquals(ParseResult.Help, parse("--help"))
        assertEquals(ParseResult.Help, parse("help"))
        assertEquals(ParseResult.Help, parse("--config", "~/otro.json", "--help"))
    }

    // --- usage errors ---

    @Test
    fun `an empty command line is a usage error that only prints the usage text`() {
        val result = parse()

        assertTrue(result is ParseResult.UsageError)
        assertNull((result as ParseResult.UsageError).message)
    }

    @Test
    fun `an unknown subcommand is a usage error`() {
        assertTrue(message("descarga").contains("subcomando desconocido"))
    }

    @Test
    fun `pair names the option it is missing`() {
        assertTrue(message("pair", "--pin", "1").contains("--url"))
        assertTrue(message("pair", "--url", "http://tv:8787").contains("--pin"))
        assertTrue(message("pair").contains("obligatoria"))
    }

    @Test
    fun `an option without a value is a usage error`() {
        assertTrue(message("pair", "--url").contains("necesita un valor"))
        assertTrue(message("install-service", "--exec").contains("necesita un valor"))
        assertTrue(message("--config").contains("necesita un valor"))
    }

    @Test
    fun `an unknown option is a usage error`() {
        val text = message("pair", "--url", "http://tv:8787", "--pin", "1", "--otro", "x")

        assertTrue(text.contains("opción desconocida"))
        assertTrue(text.contains("--otro"))
    }

    @Test
    fun `an option install-service does not take is a usage error that quotes no value`() {
        val text = message("install-service", "--url", "http://tv:8787")

        assertTrue(text.contains("opción desconocida"))
        assertTrue(text.contains("--url"))
        assertFalse(text.contains("http://tv:8787"))
    }

    @Test
    fun `a loose argument where an option belongs is a usage error`() {
        assertTrue(message("logs", "7").contains("argumento suelto"))
    }

    @Test
    fun `--config twice is a usage error`() {
        assertTrue(message("--config", "a", "--config", "b", "doctor").contains("solo puede darse una vez"))
    }

    @Test
    fun `a url that is not an http url with a host is a usage error`() {
        assertTrue(message("pair", "--url", "ftp://tv", "--pin", "1").contains("--url"))
        assertTrue(message("pair", "--url", "192.168.1.20:8787", "--pin", "1").contains("--url"))
        assertTrue(message("pair", "--url", "http://", "--pin", "1").contains("--url"))
        assertTrue(message("pair", "--url", "   ", "--pin", "1").contains("--url"))
    }

    @Test
    fun `a level the TV does not know is a usage error that lists the ones it does`() {
        val text = message("logs", "--level", "trace")

        assertTrue(text.contains("--level"))
        assertTrue(text.contains("debug, info, warn, error"))
    }

    @Test
    fun `a cursor or a page size that is not a usable number is a usage error`() {
        assertTrue(message("logs", "--since", "ayer").contains("--since"))
        assertTrue(message("logs", "--since", "-1").contains("--since"))
        assertTrue(message("logs", "--limit", "0").contains("--limit"))
        assertTrue(message("logs", "--limit", "muchos").contains("--limit"))
        assertTrue(message("logs", "--limit", "99999999999999").contains("--limit"))
    }

    @Test
    fun `no usage error quotes the value it refused, which could be the PIN`() {
        val pin = "482916"
        val messages =
            listOf(
                error("pair", "--url", pin, "--pin", "x").message,
                error("pair", "--url", "", "--pin", "x").message,
                error("logs", "--level", pin).message,
                error(pin).message,
                error("--config", pin, pin).message,
            )

        messages.forEach { assertTrue(it == null || !it.contains(pin)) }
    }

    @Test
    fun `a parsed pair command does not print the PIN`() {
        val command = Command.Pair("http://tv:8787", "482916", "salon")

        assertFalse(command.toString().contains("482916"))
        assertTrue(command.toString().contains("<redacted>"))
    }

    @Test
    fun `the usage text names every subcommand and the exit codes`() {
        val expected =
            listOf(
                "pair",
                "unpair",
                "doctor",
                "logs",
                "run",
                "install-service",
                "--config",
                "--exec",
                "--help",
                "Códigos de salida",
            )

        expected.forEach { assertTrue(ArgsParser.USAGE.contains(it)) }
    }
}
