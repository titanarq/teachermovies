package com.teachermovies.bridge.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * The test side of [FakeClaudeMain] (#276): writes the executable wrapper script that starts it on
 * this test JVM's own `java` and classpath, scripts its replies and reads back its logs. [dir] is
 * the fake's `$FAKE_CLAUDE_DIR`, baked into the script so the transport's stripped environment
 * cannot lose it.
 */
class FakeClaude(
    val dir: Path,
) {
    /** The fake `claude`, under [dir]; [installAt] puts another copy wherever a test needs one. */
    val executable: Path = installAt(dir.resolve("bin").resolve("claude"))

    fun installAt(path: Path): Path {
        Files.createDirectories(dir)
        Files.createDirectories(requireNotNull(path.parent))
        val java = quote(Paths.get(System.getProperty("java.home"), "bin", "java").toString())
        val classpath = quote(System.getProperty("java.class.path"))
        val main = FakeClaudeMain::class.java.name
        val script =
            """
            |#!/bin/sh
            |FAKE_CLAUDE_DIR=${quote(dir.toString())}
            |export FAKE_CLAUDE_DIR
            |exec $java -XX:TieredStopAtLevel=1 -cp $classpath $main "${'$'}@"
            |
            """.trimMargin()
        Files.writeString(path, script)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        return path
    }

    /** Directives for the first turn of each process to start, in start order. */
    fun warmup(vararg directives: String) = appendLines("warmup.jsonl", directives)

    /** Directives for every later turn, across all processes, in the order they are asked. */
    fun replies(vararg directives: String) = appendLines("replies.jsonl", directives)

    fun authAnswer(
        json: String,
        exitCode: Int = 0,
    ) {
        Files.writeString(dir.resolve("auth.json"), json)
        Files.writeString(dir.resolve("auth.exit"), exitCode.toString())
    }

    fun starts(): List<JsonObject> = readLines("starts.log").map { JSON.parseToJsonElement(it) as JsonObject }

    fun startedPids(): List<Long> = starts().map { (it["pid"] as JsonPrimitive).long }

    fun turns(): List<Turn> =
        readLines("turns.log").map { line ->
            val (pid, phase, text) = line.split("\t", limit = 3)
            Turn(pid.toLong(), phase, text)
        }

    /** Waits until [condition] holds, for at most [timeoutMs]; fails otherwise. */
    fun awaitUntil(
        what: String,
        timeoutMs: Long = 20_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(POLL_MS)
        }
    }

    private fun appendLines(
        name: String,
        lines: Array<out String>,
    ) {
        Files.createDirectories(dir)
        Files.write(
            dir.resolve(name),
            lines.joinToString("") { it + "\n" }.toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private fun readLines(name: String): List<String> {
        val file = dir.resolve(name)
        if (!Files.exists(file)) return emptyList()
        return Files.readAllLines(file).filter { it.isNotBlank() }
    }

    data class Turn(
        val pid: Long,
        val phase: String,
        val text: String,
    )

    private companion object {
        const val POLL_MS = 50L
        val JSON = Json { ignoreUnknownKeys = true }

        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
