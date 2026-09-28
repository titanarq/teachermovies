package com.teachermovies.bridge.cli

import com.teachermovies.bridge.config.BridgeConfigStore
import com.teachermovies.bridge.explain.ExplainHandler
import com.teachermovies.bridge.run.RunLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** What `run` answers jobs with (#291): the explain handler over a Claude conversation of its own. */
class JobHandlersTest {
    @get:Rule val tmpFolder = TemporaryFolder()

    private val out = StringBuilder()

    private fun home(): Path =
        tmpFolder.root
            .toPath()
            .resolve("home")
            .also { Files.createDirectories(it) }

    private fun store(home: Path) = BridgeConfigStore(home.resolve(".config/teachermovies-bridge/config.json"))

    private fun log(home: Path) = RunLog(out, home.resolve("bridge.log"))

    private fun executable(home: Path): Path {
        val file = home.resolve("bin/claude")
        Files.createDirectories(file.parent)
        Files.writeString(file, "#!/bin/sh\nexit 1\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"))
        return file
    }

    @Test
    fun `with a CLI there is one explain handler over one explain conversation, nothing started`() =
        runBlocking {
            val home = home()
            val cli = executable(home)
            val env = { name: String -> if (name == "CLAUDE_BIN") cli.toString() else null }
            val handlers = JobHandlers.claude(home, env, store(home), log(home))
            assertEquals(listOf(ExplainHandler.KIND), handlers.handlers.map { it.kind })
            assertEquals(listOf(ExplainHandler.KIND), handlers.conversations.map { it.kind })
            assertTrue(out.isEmpty())
            handlers.conversations.forEach { it.close() }
        }

    @Test
    fun `no CLI anywhere is no handler, said once in the log`() {
        val home = home()
        val handlers = JobHandlers.claude(home, { null }, store(home), log(home))
        assertSame(JobHandlers.NONE, handlers)
        assertTrue(out.toString(), out.contains("No se encuentra 'claude'"))
    }

    @Test
    fun `an unusable CLAUDE_BIN is no handler`() {
        val home = home()
        val env = { name: String -> if (name == "CLAUDE_BIN") home.resolve("nada").toString() else null }
        assertSame(JobHandlers.NONE, JobHandlers.claude(home, env, store(home), log(home)))
        assertTrue(out.toString(), out.contains("no se puede ejecutar"))
    }

    @Test
    fun `corrupt Claude settings are no handler`() {
        val home = home()
        val cli = executable(home)
        val store = store(home)
        Files.createDirectories(store.path.parent)
        Files.writeString(store.path.resolveSibling("claude.json"), """{"model":"-x"}""")
        val env = { name: String -> if (name == "CLAUDE_BIN") cli.toString() else null }
        assertSame(JobHandlers.NONE, JobHandlers.claude(home, env, store, log(home)))
        assertTrue(out.toString(), out.contains("no válidos"))
    }
}
