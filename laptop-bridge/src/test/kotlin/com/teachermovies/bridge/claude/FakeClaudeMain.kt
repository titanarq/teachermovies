package com.teachermovies.bridge.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

/**
 * A stand-in for the `claude` executable (#276), speaking the `stream-json` subset the transport
 * reads, started through the wrapper script [FakeClaude] writes. Never talks to the network.
 *
 * Everything is under `$FAKE_CLAUDE_DIR`:
 * - `auth status --json` prints `auth.json` (a signed-in default when absent) and exits with the
 *   code in `auth.exit` (0 when absent).
 * - Any other invocation refuses a relative or missing `--system-prompt-file` like the real CLI,
 *   then logs one JSON line per process to `starts.log` (pid, argv, cwd, how many entries the cwd
 *   holds, the system prompt, which `ANTHROPIC_*` variables it got) and one line per turn to
 *   `turns.log` (`pid<TAB>warmup|job<TAB>text`).
 * - A process's first turn takes the next line of `warmup.jsonl`, every later turn the next line
 *   of `replies.jsonl`; each queue is shared by every process (claimed with `claims/<queue>-<n>`
 *   files) and a missing line is a plain `ok`. A line is a JSON object with, all optional:
 *   `text`, `cost` (this turn's), `apiKeySource` (on the first turn's `init`, default `none`),
 *   `noInit`, `error` (an `api_error_status`), `stderr`, `exit` (die with that code), `hang`.
 */
object FakeClaudeMain {
    private val JSON = Json { ignoreUnknownKeys = true }

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = Paths.get(requireNotNull(System.getenv("FAKE_CLAUDE_DIR")))
        if (args.size >= 2 && args[0] == "auth" && args[1] == "status") auth(dir)
        val promptArg = args.indexOf("--system-prompt-file").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val prompt = promptArg?.let { Paths.get(it) }
        if (prompt == null || !prompt.isAbsolute || !Files.isRegularFile(prompt)) {
            System.err.println("Error: system prompt file not found: $promptArg")
            exitProcess(1)
        }
        val pid = ProcessHandle.current().pid()
        val cwd = Paths.get("").toAbsolutePath()
        val start =
            buildJsonObject {
                put("pid", pid)
                put("argv", buildJsonArray { args.forEach { add(JsonPrimitive(it)) } })
                put("cwd", cwd.toString())
                put("cwdEntries", Files.list(cwd).use { it.count() })
                put("systemPrompt", Files.readString(prompt))
                put(
                    "apiKeyVars",
                    buildJsonArray {
                        ClaudeCommandLine.STRIPPED_ENVIRONMENT
                            .filter {
                                System.getenv(
                                    it,
                                ) != null
                            }.forEach { add(JsonPrimitive(it)) }
                    },
                )
            }
        append(dir.resolve("starts.log"), start.toString())
        serve(dir, pid)
    }

    private fun serve(
        dir: Path,
        pid: Long,
    ) {
        val stdin = System.`in`.bufferedReader(StandardCharsets.UTF_8)
        var total = 0.0
        var first = true
        while (true) {
            val line = stdin.readLine() ?: exitProcess(0)
            val phase = if (first) "warmup" else "job"
            append(dir.resolve("turns.log"), "$pid\t$phase\t${turnText(line)}")
            val directive = claim(dir, if (first) "warmup" else "replies")
            if (first && directive.flag("noInit") != true) {
                val source = directive.text("apiKeySource") ?: "none"
                emit("""{"type":"system","subtype":"init","apiKeySource":${JsonPrimitive(source)},"model":"sonnet"}""")
            }
            first = false
            directive.text("stderr")?.let {
                System.err.println(it)
                System.err.flush()
            }
            (directive["exit"] as? JsonPrimitive)?.intOrNull?.let { exitProcess(it) }
            if (directive.flag("hang") == true) Thread.sleep(Long.MAX_VALUE)
            total += (directive["cost"] as? JsonPrimitive)?.doubleOrNull ?: DEFAULT_COST
            val error = (directive["error"] as? JsonPrimitive)?.intOrNull
            if (error != null) {
                emit(
                    """{"type":"result","subtype":"success","is_error":true,"api_error_status":$error,""" +
                        """"result":"API Error: $error","total_cost_usd":$total}""",
                )
                continue
            }
            val text = JsonPrimitive(directive.text("text") ?: "ok")
            emit("""{"type":"stream_event","event":{"delta":{"type":"text_delta","text":$text}}}""")
            emit("""{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":$text}]}}""")
            emit("""{"type":"result","subtype":"success","is_error":false,"result":$text,"total_cost_usd":$total}""")
        }
    }

    private fun auth(dir: Path): Nothing {
        val answer = dir.resolve("auth.json")
        println(
            if (Files.exists(
                    answer,
                )
            ) {
                Files.readString(
                    answer,
                )
            } else {
                """{"loggedIn":true,"authMethod":"claude.ai","subscriptionType":"max"}"""
            },
        )
        System.out.flush()
        val exit = dir.resolve("auth.exit")
        exitProcess(if (Files.exists(exit)) Files.readString(exit).trim().toInt() else 0)
    }

    private fun turnText(line: String): String {
        val content = ((JSON.parseToJsonElement(line) as JsonObject)["message"] as JsonObject)["content"] as JsonArray
        return content.joinToString("") { ((it as JsonObject)["text"] as JsonPrimitive).content }
    }

    /** The next unclaimed line of `<queue>.jsonl`, or an empty directive. */
    private fun claim(
        dir: Path,
        queue: String,
    ): JsonObject {
        val claims = Files.createDirectories(dir.resolve("claims"))
        val file = dir.resolve("$queue.jsonl")
        val lines = if (Files.exists(file)) Files.readAllLines(file).filter { it.isNotBlank() } else emptyList()
        var index = 0
        while (true) {
            try {
                Files.createFile(claims.resolve("$queue-$index"))
                break
            } catch (_: FileAlreadyExistsException) {
                index += 1
            }
        }
        val line = lines.getOrNull(index) ?: return JsonObject(emptyMap())
        return JSON.parseToJsonElement(line) as JsonObject
    }

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.flag(name: String): Boolean? = (get(name) as? JsonPrimitive)?.booleanOrNull

    private fun emit(line: String) {
        println(line)
        System.out.flush()
    }

    @Synchronized
    private fun append(
        file: Path,
        line: String,
    ) {
        Files.write(
            file,
            (line + "\n").toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private const val DEFAULT_COST = 0.001
}
