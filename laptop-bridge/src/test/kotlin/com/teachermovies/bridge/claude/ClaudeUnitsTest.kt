package com.teachermovies.bridge.claude

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneOffset

/** The pure pieces of the Claude transport (#276): argv, environment, events, cap, settings, auth, fake. */
class ClaudeUnitsTest {
    @get:Rule val tmpFolder = TemporaryFolder()

    // --- command line ---

    @Test
    fun `argv is the howto's invocation with the configured model and effort`() {
        val argv =
            ClaudeCommandLine.argv(
                Paths.get("/opt/claude/bin/claude"),
                ClaudeSettings(model = "sonnet", effort = "low"),
                Paths.get("/tmp/prompts/translate.md"),
            )

        assertEquals(
            listOf(
                "/opt/claude/bin/claude",
                "-p",
                "--input-format",
                "stream-json",
                "--output-format",
                "stream-json",
                "--verbose",
                "--model",
                "sonnet",
                "--effort",
                "low",
                "--system-prompt-file",
                "/tmp/prompts/translate.md",
                "--tools",
                "",
                "--strict-mcp-config",
                "--setting-sources",
                "",
                "--disable-slash-commands",
                "--no-session-persistence",
            ),
            argv,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a relative system prompt file is refused`() {
        ClaudeCommandLine.argv(Paths.get("/usr/bin/claude"), ClaudeSettings(), Paths.get("prompts/x.md"))
    }

    @Test
    fun `the child environment loses both Anthropic credentials and keeps the rest`() {
        val child =
            ClaudeCommandLine.childEnvironment(
                mapOf(
                    "PATH" to "/usr/bin",
                    "HOME" to "/home/u",
                    "ANTHROPIC_API_KEY" to "k",
                    "ANTHROPIC_AUTH_TOKEN" to "t",
                ),
            )

        assertEquals(mapOf("PATH" to "/usr/bin", "HOME" to "/home/u"), child)
    }

    @Test
    fun `the Claude directory follows XDG_CACHE_HOME and is always absolute`() {
        val home = Paths.get("/home/u")

        assertEquals(Paths.get("/home/u/.cache/teachermovies-bridge/claude"), ClaudeDirs.default(home) { null }.root)
        assertEquals(
            Paths.get("/home/u/cache/teachermovies-bridge/claude"),
            ClaudeDirs.default(home) { if (it == "XDG_CACHE_HOME") "~/cache" else null }.root,
        )
    }

    // --- events ---

    @Test
    fun `init, assistant text and results are read off stream-json lines`() {
        assertEquals(
            ClaudeEvent.Init("none"),
            ClaudeEvents.parse("""{"type":"system","subtype":"init","apiKeySource":"none","tools":[]}"""),
        )
        assertEquals(
            ClaudeEvent.AssistantText("ho" + "la"),
            ClaudeEvents.parse(
                """{"type":"assistant","message":{"content":[{"type":"text","text":"ho"},{"type":"thinking"},{"type":"text","text":"la"}]}}""",
            ),
        )
        val ok =
            ClaudeEvents.parse(
                """{"type":"result","subtype":"success","is_error":false,"result":"hola","total_cost_usd":0.5}""",
            )
        assertEquals(ClaudeEvent.Result(false, "success", null, 0.5, "hola"), ok)
        assertEquals(ClaudeEvent.Other, ClaudeEvents.parse("""{"type":"stream_event","event":{}}"""))
        assertEquals(ClaudeEvent.Unreadable, ClaudeEvents.parse("no es json"))
        assertEquals(ClaudeEvent.Unreadable, ClaudeEvents.parse("[1,2]"))
    }

    @Test
    fun `a 429 is recognised by its status, as a number or a string, or by the text when there is no status`() {
        val numeric =
            ClaudeEvents.parse(
                """{"type":"result","is_error":true,"api_error_status":429}""",
            ) as ClaudeEvent.Result
        val string =
            ClaudeEvents.parse(
                """{"type":"result","is_error":true,"api_error_status":"429"}""",
            ) as ClaudeEvent.Result
        val textOnly =
            ClaudeEvents.parse(
                """{"type":"result","is_error":true,"result":"API Error: 429 rate_limit"}""",
            ) as ClaudeEvent.Result
        val server =
            ClaudeEvents.parse(
                """{"type":"result","is_error":true,"api_error_status":529,"result":"429?"}""",
            ) as ClaudeEvent.Result
        val subtype =
            ClaudeEvents.parse(
                """{"type":"result","subtype":"error_during_execution"}""",
            ) as ClaudeEvent.Result

        assertTrue(numeric.isRateLimit)
        assertTrue(string.isRateLimit)
        assertTrue(textOnly.isRateLimit)
        assertFalse(server.isRateLimit)
        assertTrue(subtype.isError)
    }

    @Test
    fun `a turn is one user message with one text block`() {
        val line = ClaudeEvents.userTurn("di \"hola\"\nya")
        val parsed = Json.parseToJsonElement(line) as JsonObject

        assertFalse(line.contains("\n"))
        assertEquals("user", (parsed["type"] as JsonPrimitive).content)
        val message = parsed["message"] as JsonObject
        assertEquals("user", (message["role"] as JsonPrimitive).content)
        val block = (message["content"] as JsonArray).single() as JsonObject
        assertEquals("di \"hola\"\nya", (block["text"] as JsonPrimitive).content)
    }

    // --- daily cap ---

    @Test
    fun `the daily cap counts jobs per day and starts over the next day`() {
        var now = Instant.parse("2026-09-28T22:00:00Z")
        val cap = DailyCap(2, clock = { now }, zone = ZoneOffset.UTC)

        assertTrue(cap.tryAcquire())
        assertTrue(cap.tryAcquire())
        assertFalse(cap.tryAcquire())
        assertTrue(cap.isReached())
        assertEquals(2, cap.usedToday())

        now = Instant.parse("2026-09-29T00:00:01Z")
        assertFalse(cap.isReached())
        assertTrue(cap.tryAcquire())
        assertEquals(1, cap.usedToday())
    }

    // --- settings ---

    @Test
    fun `no settings file is the defaults, and a partial one fills in the rest`() {
        val file = ClaudeSettingsFile(tmpFolder.root.toPath().resolve(ClaudeSettingsFile.FILE_NAME))
        assertEquals(ClaudeSettingsLoad.Defaults, file.load())

        Files.writeString(file.path, """{"dailyJobCap":50,"model":"opus","futuro":true}""")

        assertEquals(ClaudeSettingsLoad.Loaded(ClaudeSettings(model = "opus", dailyJobCap = 50)), file.load())
        assertEquals("low", ClaudeSettings().effort)
        assertEquals(300, ClaudeSettings().dailyJobCap)
    }

    @Test
    fun `a settings file that is not JSON or holds an unusable value is corrupt`() {
        val file = ClaudeSettingsFile.besideConfig(tmpFolder.root.toPath().resolve("config.json"))
        assertEquals(tmpFolder.root.toPath().resolve("claude.json"), file.path)

        Files.writeString(file.path, "{")
        assertTrue(file.load() is ClaudeSettingsLoad.Corrupt)

        Files.writeString(file.path, """{"model":"--dangerously-skip-permissions"}""")
        assertEquals(ClaudeSettingsLoad.Corrupt("valor no válido en 'model'"), file.load())

        Files.writeString(file.path, """{"dailyJobCap":0}""")
        assertEquals(ClaudeSettingsLoad.Corrupt("valor no válido en 'dailyJobCap'"), file.load())
    }

    // --- auth status ---

    @Test
    fun `auth status is parsed from its JSON whatever the exit code`() {
        assertEquals(
            AuthStatus.LoggedIn("claude.ai", "max"),
            ClaudeAuth.parse("""{"loggedIn":true,"authMethod":"claude.ai","subscriptionType":"max"}""", 0, emptyList()),
        )
        assertEquals(AuthStatus.LoggedOut, ClaudeAuth.parse("""{"loggedIn":false}""", 1, emptyList()))
        assertTrue(ClaudeAuth.parse("Usage: claude ...", 2, listOf("error")) is AuthStatus.Failed)
        assertTrue(ClaudeAuth.parse("""{"authMethod":"x"}""", 0, emptyList()) is AuthStatus.Failed)
    }

    @Test
    fun `auth status runs the CLI without the Anthropic credentials`() {
        val fake = FakeClaude(tmpFolder.root.toPath().resolve("fake"))

        assertEquals(
            AuthStatus.LoggedIn("claude.ai", "max"),
            ClaudeAuth.status(
                fake.executable,
                System.getenv() + ("ANTHROPIC_API_KEY" to "k"),
            ),
        )

        fake.authAnswer("""{"loggedIn":false}""", exitCode = 1)
        assertEquals(AuthStatus.LoggedOut, ClaudeAuth.status(fake.executable))

        val missing = ClaudeAuth.status(tmpFolder.root.toPath().resolve("no-existe"))
        assertTrue(missing is AuthStatus.Failed)
    }

    // --- fake ---

    @Test
    fun `the fake CLI answers its queue in order, then its fallback, and records every prompt`() {
        val fake = FakeClaudeCli("explain", listOf(ClaudeOutcome.RateLimited))

        val outcomes = runBlocking { listOf(fake.ask("uno"), fake.ask("dos")) }

        assertEquals(ClaudeOutcome.RateLimited, outcomes[0])
        assertTrue(outcomes[1] is ClaudeOutcome.Answered)
        assertEquals(listOf("uno", "dos"), fake.prompts)
        assertNull(outcomes[1].failureResult())
    }
}
