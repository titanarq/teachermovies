package com.teachermovies.bridge.claude

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The last lines a process wrote to stderr (#276): what turns "the process died" into "system prompt
 * file not found: /wrong/path". Bounded, so a chatty process never grows the bridge's memory.
 */
class StderrTail(
    private val maxLines: Int = DEFAULT_LINES,
) {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        lines.addLast(line.take(MAX_LINE_CHARS))
        while (lines.size > maxLines) lines.removeFirst()
    }

    @Synchronized
    fun lines(): List<String> = lines.toList()

    companion object {
        const val DEFAULT_LINES = 40
        private const val MAX_LINE_CHARS = 500
    }
}

/** How one turn of a [ClaudeProcess] ended. */
internal sealed interface TurnRead {
    /** The turn's `result` event, the assistant text before it, and every `init` seen on the way. */
    data class Completed(
        val result: ClaudeEvent.Result,
        val text: String,
        val inits: List<ClaudeEvent.Init>,
    ) : TurnRead

    /** stdout closed before a `result`: the process is gone, [exitCode] null if it would not say. */
    data class Exited(
        val exitCode: Int?,
    ) : TurnRead

    /** No `result` within the turn's timeout. The process is still running; the caller kills it. */
    data object TimedOut : TurnRead
}

/**
 * One running `claude -p --input-format stream-json --output-format stream-json` (#276): stdin takes
 * one [ClaudeEvents.userTurn] line per turn, stdout is read line by line on [scope]'s IO threads into
 * a channel, and stderr is drained into [stderr] so the pipe never fills and the tail is there when
 * the process dies. One turn at a time: the caller serialises [turn] calls.
 *
 * [cwd] and [promptFile] are this process's own and are deleted by [close]/[kill].
 */
internal class ClaudeProcess private constructor(
    private val process: Process,
    private val cwd: Path,
    private val promptFile: Path,
    scope: CoroutineScope,
) {
    val stderr = StderrTail()
    private val stdin: Writer = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)
    private val lines = Channel<String>(Channel.UNLIMITED)
    private val stdoutJob: Job
    private val stderrJob: Job

    val pid: Long get() = process.pid()
    val isAlive: Boolean get() = process.isAlive

    /** Successful and failed turns this process has answered, the warm-up included. */
    var turns: Int = 0
        private set

    /** The last `total_cost_usd` this process reported: its running total. */
    private var totalCostUsd: Double = 0.0

    init {
        stdoutJob =
            scope.launch(Dispatchers.IO) {
                try {
                    process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { input ->
                        for (line in input) lines.send(line)
                    }
                } catch (_: IOException) {
                    // The process was killed under the reader: the same end of stdout as an EOF.
                } finally {
                    lines.close()
                }
            }
        stderrJob =
            scope.launch(Dispatchers.IO) {
                try {
                    process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { input ->
                        for (line in input) stderr.add(line)
                    }
                } catch (_: IOException) {
                    // Killed: whatever was read so far is the tail.
                }
            }
    }

    /**
     * Sends [prompt] as one turn and reads events until its `result`, for at most [timeout]. A
     * process that closes stdout first is [TurnRead.Exited], its stderr drained to the end.
     */
    suspend fun turn(
        prompt: String,
        timeout: Duration,
    ): TurnRead {
        val written =
            withContext(Dispatchers.IO) {
                try {
                    stdin.write(ClaudeEvents.userTurn(prompt))
                    stdin.write("\n")
                    stdin.flush()
                    true
                } catch (_: IOException) {
                    false
                }
            }
        if (!written) return exited()
        val read = withTimeoutOrNull(timeout) { readUntilResult() }
        if (read != null) {
            turns += 1
            return read
        }
        return if (lines.isClosedForReceive) exited() else TurnRead.TimedOut
    }

    /** Events up to the turn's `result`, or null when stdout ends first. */
    private suspend fun readUntilResult(): TurnRead.Completed? {
        val inits = mutableListOf<ClaudeEvent.Init>()
        val texts = StringBuilder()
        while (true) {
            val line = lines.receiveCatching().getOrNull() ?: return null
            when (val event = ClaudeEvents.parse(line)) {
                is ClaudeEvent.Init -> inits += event
                is ClaudeEvent.AssistantText -> texts.append(event.text)
                is ClaudeEvent.Result -> return TurnRead.Completed(event, texts.toString(), inits)
                ClaudeEvent.Other, ClaudeEvent.Unreadable -> Unit
            }
        }
    }

    /** This turn's share of [ClaudeEvent.Result.totalCostUsd], null when the process did not report one. */
    fun costDelta(result: ClaudeEvent.Result): Double? {
        val total = result.totalCostUsd ?: return null
        val delta = (total - totalCostUsd).coerceAtLeast(0.0)
        totalCostUsd = total
        return delta
    }

    private suspend fun exited(): TurnRead.Exited =
        withContext(Dispatchers.IO) {
            val ended = process.waitFor(EXIT_WAIT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            withTimeoutOrNull(EXIT_WAIT) { stderrJob.join() }
            TurnRead.Exited(if (ended) process.exitValue() else null)
        }

    /** Closes stdin -- the CLI exits at EOF -- and waits briefly before killing it. */
    suspend fun close() {
        withContext(Dispatchers.IO) {
            try {
                stdin.close()
            } catch (_: IOException) {
                // Already gone.
            }
            if (!process.waitFor(EXIT_WAIT.inWholeMilliseconds, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            withTimeoutOrNull(EXIT_WAIT) {
                stdoutJob.join()
                stderrJob.join()
            }
            deleteOwnFiles()
        }
    }

    /** Kills the process at once: a timed-out turn is never reused (the howto). */
    fun kill() {
        process.destroyForcibly()
        deleteOwnFiles()
    }

    private fun deleteOwnFiles() {
        try {
            Files.deleteIfExists(promptFile)
            Files.deleteIfExists(cwd)
        } catch (_: IOException) {
            // A working directory the CLI wrote into is left behind rather than emptied here.
        }
    }

    companion object {
        private val EXIT_WAIT = 2.seconds

        /**
         * Starts [argv] in [cwd] with exactly [environment]. Throws [IOException] when the executable
         * cannot be started at all.
         */
        fun start(
            argv: List<String>,
            environment: Map<String, String>,
            cwd: Path,
            promptFile: Path,
            scope: CoroutineScope,
        ): ClaudeProcess {
            val builder = ProcessBuilder(argv).directory(cwd.toFile())
            builder.environment().clear()
            builder.environment().putAll(environment)
            return ClaudeProcess(builder.start(), cwd, promptFile, scope)
        }
    }
}
