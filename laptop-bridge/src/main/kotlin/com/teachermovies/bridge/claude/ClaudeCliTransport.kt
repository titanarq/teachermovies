package com.teachermovies.bridge.claude

import com.teachermovies.bridge.run.RunLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The Claude Code CLI transport of #276 (ADR-0005 §3): one long-lived
 * `claude -p --input-format stream-json --output-format stream-json` process for one job [kind],
 * billed only to the human's own CLI login on this laptop.
 *
 * - Every process is started with [ClaudeCommandLine.argv] -- no tools, no MCP, no settings, no
 *   slash commands, no session transcript -- in a fresh, empty, private working directory under
 *   [dirs], with [systemPrompt] in an absolute `--system-prompt-file`, and with the environment
 *   [baseEnvironment] minus `ANTHROPIC_API_KEY`/`ANTHROPIC_AUTH_TOKEN`.
 * - Its first turn is a warm-up ([WARM_UP_PROMPT]) before any job reaches it. The `init` event that
 *   turn brings must report `apiKeySource` `none`; anything else -- or no `init` at all -- and this
 *   transport kills the process and answers [ClaudeOutcome.Refused] to every job until the bridge
 *   restarts, without starting another process that would bill the same key.
 * - As soon as one process is ready, a replacement is started and warmed up in the background, so
 *   a rotation -- after [ClaudeSettings.rotateAfterTurns] jobs, a turn that outlives
 *   [ClaudeSettings.turnTimeoutSeconds] (the process is killed), or a process that died -- hands the
 *   next job to a process that is already waiting.
 * - A `429` is [ClaudeOutcome.RateLimited]; [cap] is checked before each job; each turn's cost
 *   delta goes to [log] tagged `billing=subscription`; the stderr tail of a process that died is
 *   written to [ClaudeDirs.stderrTail] for `doctor`.
 *
 * Jobs are answered one at a time, in the order they arrive.
 */
class ClaudeCliTransport(
    override val kind: String,
    private val systemPrompt: String,
    private val executable: Path,
    private val settings: ClaudeSettings,
    private val dirs: ClaudeDirs,
    private val cap: DailyCap,
    private val log: RunLog,
    private val baseEnvironment: Map<String, String> = System.getenv(),
) : ClaudeCli {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val turnTimeout = settings.turnTimeoutSeconds.seconds
    private val environment = ClaudeCommandLine.childEnvironment(baseEnvironment)

    private var current: ClaudeProcess? = null
    private var spare: Deferred<Spawn>? = null

    @Volatile private var refusal: ClaudeOutcome.Refused? = null

    init {
        require(settings.problem() == null) { "unusable Claude settings: ${settings.problem()}" }
    }

    override suspend fun warmUp(): ClaudeOutcome? =
        mutex.withLock {
            when (val ready = ready()) {
                is Spawn.Ready -> null
                is Spawn.Failed -> ready.outcome
            }
        }

    override suspend fun ask(prompt: String): ClaudeOutcome =
        mutex.withLock {
            refusal?.let { return it }
            if (cap.isReached()) return capReached()
            val process =
                when (val ready = ready()) {
                    is Spawn.Ready -> ready.process
                    is Spawn.Failed -> return ready.outcome
                }
            if (!cap.tryAcquire()) return capReached()
            job(process, prompt)
        }

    override suspend fun close() {
        mutex.withLock {
            val pending = spare
            spare = null
            pending?.cancel()
            val spawned =
                try {
                    pending?.await()
                } catch (_: CancellationException) {
                    null
                }
            (spawned as? Spawn.Ready)?.process?.close()
            current?.close()
            current = null
        }
        scope.cancel()
    }

    /** The process for the next turn: the current one, else the warmed-up spare, else a new one. */
    private suspend fun ready(): Spawn {
        current?.let {
            if (it.isAlive) return Spawn.Ready(it)
            log("el proceso ${it.pid} ha terminado sin avisar; se sustituye")
            it.kill()
            current = null
        }
        val pending = spare
        spare = null
        val fromSpare = pending?.await()
        val spawned =
            when {
                fromSpare is Spawn.Ready && fromSpare.process.isAlive -> {
                    fromSpare
                }

                else -> {
                    (fromSpare as? Spawn.Ready)?.process?.kill()
                    refusal?.let { return Spawn.Failed(it) }
                    spawn()
                }
            }
        if (spawned is Spawn.Ready) {
            current = spawned.process
            prespawn()
        }
        return spawned
    }

    private fun prespawn() {
        if (spare == null && refusal == null) spare = scope.async { spawn() }
    }

    /** Starts one process and runs its warm-up turn; only a warmed-up process is [Spawn.Ready]. */
    private suspend fun spawn(): Spawn {
        refusal?.let { return Spawn.Failed(it) }
        val started = TimeSource.Monotonic.markNow()
        val files =
            try {
                prepareFiles()
            } catch (e: IOException) {
                log("no se puede preparar ${dirs.root} (${e::class.simpleName})")
                return Spawn.Failed(ClaudeOutcome.Failed("no se puede preparar ${dirs.root}"))
            }
        val argv = ClaudeCommandLine.argv(executable, settings, files.promptFile)
        val process =
            try {
                ClaudeProcess.start(argv, environment, files.cwd, files.promptFile, scope)
            } catch (e: IOException) {
                files.delete()
                log("no se puede arrancar $executable (${e::class.simpleName})")
                return Spawn.Failed(ClaudeOutcome.Failed("no se puede arrancar $executable"))
            }
        try {
            val outcome = settle(process, process.turn(WARM_UP_PROMPT, turnTimeout), started, WARM_UP_LABEL)
            if (outcome is ClaudeOutcome.Answered) return Spawn.Ready(process)
            process.kill()
            return Spawn.Failed(outcome)
        } catch (e: CancellationException) {
            process.kill()
            throw e
        }
    }

    private suspend fun job(
        process: ClaudeProcess,
        prompt: String,
    ): ClaudeOutcome {
        val started = TimeSource.Monotonic.markNow()
        val read = process.turn(prompt, turnTimeout)
        val outcome = settle(process, read, started, JOB_LABEL)
        val jobs = process.turns - 1
        when {
            read !is TurnRead.Completed || outcome is ClaudeOutcome.Refused -> {
                current = null
            }

            jobs >= settings.rotateAfterTurns -> {
                log("rotación del proceso ${process.pid} tras $jobs trabajos")
                current = null
                scope.launch { process.close() }
            }
        }
        return outcome
    }

    /**
     * What one turn of [process] came to. Kills the process for a timeout, a death or a refused
     * billing source; a failed turn the CLI answered cleanly (a `429`, a `5xx`) leaves it running.
     */
    private fun settle(
        process: ClaudeProcess,
        read: TurnRead,
        started: TimeSource.Monotonic.ValueTimeMark,
        label: String,
    ): ClaudeOutcome {
        val elapsedMs = started.elapsedNow().inWholeMilliseconds
        return when (read) {
            TurnRead.TimedOut -> {
                process.kill()
                log(
                    "$label sin respuesta en ${settings.turnTimeoutSeconds} s: proceso ${process.pid} matado y sustituido",
                )
                ClaudeOutcome.TimedOut
            }

            is TurnRead.Exited -> {
                val tail = process.stderr.lines()
                saveStderrTail(process.pid, read.exitCode, tail)
                process.kill()
                val last = tail.lastOrNull { it.isNotBlank() }?.take(REASON_CHARS)
                val reason = "Claude Code terminó (código ${read.exitCode ?: "?"})" + (last?.let { ": $it" } ?: "")
                log("$label: $reason")
                ClaudeOutcome.Failed(reason)
            }

            is TurnRead.Completed -> {
                completed(process, read, elapsedMs, label)
            }
        }
    }

    private fun completed(
        process: ClaudeProcess,
        read: TurnRead.Completed,
        elapsedMs: Long,
        label: String,
    ): ClaudeOutcome {
        val foreign = read.inits.firstOrNull { it.apiKeySource != SUBSCRIPTION_SOURCE }
        if (foreign != null || (read.inits.isEmpty() && process.turns == 1)) {
            return refuse(process, foreign?.apiKeySource)
        }
        val cost = process.costDelta(read.result)
        val costText = cost?.let { "coste ${String.format(Locale.ROOT, "%.4f", it)} USD" } ?: "coste desconocido"
        val result = read.result
        if (result.isError) {
            val status = result.apiErrorStatus?.toString() ?: result.subtype ?: "error"
            log("$label fallido ($status) en $elapsedMs ms, $costText $BILLING_TAG")
            if (result.isRateLimit) return ClaudeOutcome.RateLimited
            return ClaudeOutcome.Failed("Claude Code ha fallado el turno ($status)")
        }
        val prefix = if (label == WARM_UP_LABEL) "proceso ${process.pid} listo, $label" else label
        log("$prefix en $elapsedMs ms, $costText $BILLING_TAG")
        val text = read.text.ifEmpty { result.text.orEmpty() }
        return ClaudeOutcome.Answered(text, cost, elapsedMs)
    }

    private fun refuse(
        process: ClaudeProcess,
        source: String?,
    ): ClaudeOutcome.Refused {
        process.kill()
        val refused = ClaudeOutcome.Refused(source)
        refusal = refused
        log(
            "Claude Code informa apiKeySource=${source?.take(REASON_CHARS) ?: "(ninguno)"} en vez de " +
                "'$SUBSCRIPTION_SOURCE': no se le pedirá nada más hasta reiniciar el puente con la sesión del CLI " +
                "(claude auth login) y sin ANTHROPIC_API_KEY",
        )
        return refused
    }

    private fun capReached(): ClaudeOutcome.DailyCapReached {
        log("tope diario de ${cap.limit} trabajos alcanzado; no se pregunta a Claude hasta mañana")
        return ClaudeOutcome.DailyCapReached(cap.limit)
    }

    private fun prepareFiles(): ProcessFiles {
        val tag = "$kind-${UUID.randomUUID().toString().take(TAG_CHARS)}"
        createPrivateDirectories(dirs.workDirs)
        createPrivateDirectories(dirs.prompts)
        val cwd = Files.createDirectory(dirs.workDirs.resolve(tag), PRIVATE_DIR)
        val promptFile = Files.createFile(dirs.prompts.resolve("$tag.md"), PRIVATE_FILE)
        Files.writeString(promptFile, systemPrompt)
        return ProcessFiles(cwd, promptFile)
    }

    private fun saveStderrTail(
        pid: Long,
        exitCode: Int?,
        tail: List<String>,
    ) {
        val path = dirs.stderrTail(kind)
        val text =
            buildString {
                appendLine("# ${Instant.now()} proceso $pid, código ${exitCode ?: "?"}")
                tail.forEach { appendLine(it) }
            }
        try {
            createPrivateDirectories(dirs.root)
            Files.deleteIfExists(path)
            Files.createFile(path, PRIVATE_FILE)
            Files.write(path, text.toByteArray(StandardCharsets.UTF_8))
        } catch (e: IOException) {
            log("no se puede guardar el stderr de Claude Code en $path (${e::class.simpleName})")
        }
    }

    private fun log(message: String) {
        log.line("claude $kind: $message")
    }

    private sealed interface Spawn {
        data class Ready(
            val process: ClaudeProcess,
        ) : Spawn

        data class Failed(
            val outcome: ClaudeOutcome,
        ) : Spawn
    }

    private class ProcessFiles(
        val cwd: Path,
        val promptFile: Path,
    ) {
        fun delete() {
            try {
                Files.deleteIfExists(promptFile)
                Files.deleteIfExists(cwd)
            } catch (_: IOException) {
                // Left behind; the next process gets fresh names anyway.
            }
        }
    }

    companion object {
        /** The first turn of every process, before any job: its answer is thrown away. */
        const val WARM_UP_PROMPT = "Turno de calentamiento del puente teachermovies: responde solo \"listo\"."

        /** What `init.apiKeySource` reports under the human's CLI login. */
        const val SUBSCRIPTION_SOURCE = "none"

        const val BILLING_TAG = "billing=subscription"

        private const val WARM_UP_LABEL = "calentamiento"
        private const val JOB_LABEL = "trabajo"
        private const val REASON_CHARS = 200
        private const val TAG_CHARS = 8

        private val PRIVATE_DIR = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        private val PRIVATE_FILE = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

        private fun createPrivateDirectories(path: Path) {
            if (!Files.isDirectory(path)) Files.createDirectories(path, PRIVATE_DIR)
        }
    }
}
