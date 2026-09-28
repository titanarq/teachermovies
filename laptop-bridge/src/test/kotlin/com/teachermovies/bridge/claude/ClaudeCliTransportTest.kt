package com.teachermovies.bridge.claude

import com.teachermovies.bridge.run.RunLog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Paths

/**
 * [ClaudeCliTransport] driving real processes (#276): a fake `claude` ([FakeClaudeMain]) started
 * through its wrapper script, asserted against the fake's own logs of what it was started with
 * and what each turn received. No network, no real CLI.
 */
class ClaudeCliTransportTest {
    @get:Rule val tmpFolder = TemporaryFolder()

    private val fake by lazy { FakeClaude(tmpFolder.root.toPath().resolve("fake")) }
    private val dirs by lazy { ClaudeDirs(tmpFolder.root.toPath().resolve("cache")) }
    private val logOut = StringBuilder()
    private val log = RunLog(logOut, null)
    private val transports = mutableListOf<ClaudeCliTransport>()

    private val systemPrompt = "Eres el traductor del puente.\nResponde en JSON."
    private val environmentWithKeys =
        System.getenv() + mapOf("ANTHROPIC_API_KEY" to "sk-ant-secreta", "ANTHROPIC_AUTH_TOKEN" to "token-secreto")

    @After
    fun closeAll() {
        runBlocking { transports.forEach { it.close() } }
    }

    private fun transport(
        settings: ClaudeSettings = ClaudeSettings(turnTimeoutSeconds = 20),
        cap: DailyCap = DailyCap(ClaudeSettings.DEFAULT_DAILY_JOB_CAP),
    ): ClaudeCliTransport =
        ClaudeCliTransport(
            kind = "translate",
            systemPrompt = systemPrompt,
            executable = fake.executable,
            settings = settings,
            dirs = dirs,
            cap = cap,
            log = log,
            baseEnvironment = environmentWithKeys,
        ).also { transports += it }

    private fun logText(): String = synchronized(log) { logOut.toString() }

    private fun jobTurns() = fake.turns().filter { it.phase == "job" }

    @Test
    fun `a job reaches a warmed-up process started with the howto's flags, an empty private cwd and no API key`() {
        fake.replies("""{"text":"hola"}""")
        val settings = ClaudeSettings(turnTimeoutSeconds = 20)

        val outcome = runBlocking { transport(settings).ask("traduce: hello") }

        assertEquals("hola", (outcome as ClaudeOutcome.Answered).text)
        val start = fake.starts().first()
        val argv = (start["argv"] as JsonArray).map { (it as JsonPrimitive).content }
        val promptFile = Paths.get(argv[argv.indexOf("--system-prompt-file") + 1])
        assertTrue(promptFile.isAbsolute)
        assertEquals(
            listOf(fake.executable.toString()) + argv,
            ClaudeCommandLine.argv(fake.executable, settings, promptFile),
        )
        assertEquals("", argv[argv.indexOf("--tools") + 1])
        assertEquals("", argv[argv.indexOf("--setting-sources") + 1])
        assertEquals(systemPrompt, (start["systemPrompt"] as JsonPrimitive).content)
        val cwd = Paths.get((start["cwd"] as JsonPrimitive).content)
        assertTrue(cwd.isAbsolute)
        assertEquals(dirs.workDirs, cwd.parent)
        assertEquals(0L, (start["cwdEntries"] as JsonPrimitive).long)
        assertEquals(0, (start["apiKeyVars"] as JsonArray).size)
        val pid = (start["pid"] as JsonPrimitive).long
        val ofFirst = fake.turns().filter { it.pid == pid }
        assertEquals(
            listOf("warmup" to ClaudeCliTransport.WARM_UP_PROMPT, "job" to "traduce: hello"),
            ofFirst.map {
                it.phase to
                    it.text
            },
        )
        assertFalse(logText().contains("sk-ant-secreta"))
    }

    @Test
    fun `warmUp runs the warm-up turn before any job`() {
        val transport = transport()

        assertEquals(null, runBlocking { transport.warmUp() })

        val first = fake.turns().first()
        assertEquals("warmup", first.phase)
        assertEquals(ClaudeCliTransport.WARM_UP_PROMPT, first.text)
        assertTrue(jobTurns().isEmpty())
        assertTrue(logText().contains("listo, calentamiento"))
    }

    @Test
    fun `a replacement is started and warmed up while the first process is serving`() {
        val transport = transport()

        runBlocking { transport.warmUp() }

        fake.awaitUntil("a warmed-up spare") { fake.turns().count { it.phase == "warmup" } == 2 }
        assertEquals(2, fake.startedPids().distinct().size)
    }

    @Test
    fun `a turn that outlives the timeout kills its process and the next job goes to the waiting spare`() {
        fake.replies("""{"hang":true}""", """{"text":"segundo"}""")
        val transport = transport(ClaudeSettings(turnTimeoutSeconds = 4))
        runBlocking { transport.warmUp() }
        fake.awaitUntil("a warmed-up spare") { fake.turns().count { it.phase == "warmup" } == 2 }
        val (first, second) = fake.startedPids()

        assertEquals(ClaudeOutcome.TimedOut, runBlocking { transport.ask("uno") })
        val started = System.currentTimeMillis()
        val next = runBlocking { transport.ask("dos") }

        assertEquals("segundo", (next as ClaudeOutcome.Answered).text)
        assertTrue(System.currentTimeMillis() - started < 3_000)
        assertEquals(listOf(first, second), jobTurns().map { it.pid })
        fake.awaitUntil("the hung process to die") { ProcessHandle.of(first).map { it.isAlive }.orElse(false) == false }
        assertTrue(logText().contains("sin respuesta en 4 s"))
    }

    @Test
    fun `a process is rotated after the configured number of jobs`() {
        fake.replies("""{"text":"a"}""", """{"text":"b"}""", """{"text":"c"}""")
        val transport = transport(ClaudeSettings(turnTimeoutSeconds = 20, rotateAfterTurns = 2))

        val texts = runBlocking { listOf("1", "2", "3").map { (transport.ask(it) as ClaudeOutcome.Answered).text } }

        assertEquals(listOf("a", "b", "c"), texts)
        val pids = jobTurns().map { it.pid }
        assertEquals(pids[0], pids[1])
        assertTrue(pids[2] != pids[0])
        fake.awaitUntil("the rotated process to exit") {
            ProcessHandle.of(pids[0]).map { it.isAlive }.orElse(false) ==
                false
        }
        assertTrue(logText().contains("tras 2 trabajos"))
    }

    @Test
    fun `a 429 is rate_limited and the process keeps serving`() {
        fake.replies("""{"error":429}""", """{"text":"ya"}""")
        val transport = transport()

        val limited = runBlocking { transport.ask("uno") }
        val after = runBlocking { transport.ask("dos") }

        assertEquals(ClaudeOutcome.RateLimited, limited)
        assertEquals(ClaudeOutcome.RATE_LIMITED, limited.failureResult()?.code)
        assertEquals("ya", (after as ClaudeOutcome.Answered).text)
        assertEquals(1, jobTurns().map { it.pid }.distinct().size)
    }

    @Test
    fun `a process billed to an API key is refused before any job reaches it, and no other is started`() {
        fake.warmup("""{"apiKeySource":"ANTHROPIC_API_KEY"}""")
        val transport = transport()

        val first = runBlocking { transport.ask("uno") }
        val second = runBlocking { transport.ask("dos") }

        assertEquals(ClaudeOutcome.Refused("ANTHROPIC_API_KEY"), first)
        assertEquals(first, second)
        assertTrue(jobTurns().isEmpty())
        Thread.sleep(500)
        assertEquals(1, fake.starts().size)
        assertTrue(logText().contains("apiKeySource=ANTHROPIC_API_KEY"))
    }

    @Test
    fun `a process whose first turn brings no init is refused too`() {
        fake.warmup("""{"noInit":true}""")

        assertEquals(ClaudeOutcome.Refused(null), runBlocking { transport().ask("uno") })
        assertTrue(jobTurns().isEmpty())
    }

    @Test
    fun `a process that dies at startup is a failure carrying its stderr, which is kept for doctor`() {
        fake.warmup("""{"stderr":"Error: algo se ha roto al arrancar","exit":3}""")

        val outcome = runBlocking { transport().ask("uno") }

        val reason = (outcome as ClaudeOutcome.Failed).reason
        assertTrue(reason, reason.contains("código 3"))
        assertTrue(reason, reason.contains("algo se ha roto al arrancar"))
        assertEquals(ClaudeOutcome.CLAUDE_ERROR, outcome.failureResult()?.code)
        val tail = Files.readString(dirs.stderrTail("translate"))
        assertTrue(tail.contains("algo se ha roto al arrancar"))
        assertEquals(
            "rw-------",
            java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(dirs.stderrTail("translate")),
            ),
        )
    }

    @Test
    fun `an executable that cannot be started is a failure, not an exception`() {
        val missing =
            ClaudeCliTransport(
                "explain",
                systemPrompt,
                tmpFolder.root.toPath().resolve("no-existe/claude"),
                ClaudeSettings(),
                dirs,
                DailyCap(1),
                log,
            ).also { transports += it }

        assertTrue(runBlocking { missing.ask("uno") } is ClaudeOutcome.Failed)
    }

    @Test
    fun `each turn's cost delta is logged tagged billing=subscription`() {
        fake.warmup("""{"cost":0.25}""")
        fake.replies("""{"text":"a","cost":0.0125}""", """{"text":"b","cost":0.03}""")
        val transport = transport()

        val costs = runBlocking { listOf("1", "2").map { (transport.ask(it) as ClaudeOutcome.Answered).costUsd } }

        assertEquals(0.0125, costs[0]!!, 1e-9)
        assertEquals(0.03, costs[1]!!, 1e-9)
        val text = logText()
        assertTrue(text, text.contains("calentamiento en"))
        assertTrue(text, text.contains("coste 0.2500 USD billing=subscription"))
        assertTrue(text, text.contains("coste 0.0125 USD billing=subscription"))
        assertTrue(text, text.contains("coste 0.0300 USD billing=subscription"))
    }

    @Test
    fun `the daily cap stops jobs before they reach Claude`() {
        val transport = transport(cap = DailyCap(1))

        val first = runBlocking { transport.ask("uno") }
        val second = runBlocking { transport.ask("dos") }

        assertTrue(first is ClaudeOutcome.Answered)
        assertEquals(ClaudeOutcome.DailyCapReached(1), second)
        assertEquals(ClaudeOutcome.DAILY_CAP, second.failureResult()?.code)
        assertEquals(listOf("uno"), jobTurns().map { it.text })
    }

    @Test
    fun `close ends every process it started`() {
        val transport = transport()
        runBlocking { transport.warmUp() }
        fake.awaitUntil("a warmed-up spare") { fake.turns().count { it.phase == "warmup" } == 2 }

        runBlocking { transport.close() }

        fake.awaitUntil("every process to exit") {
            fake.startedPids().none { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }
        }
    }
}
