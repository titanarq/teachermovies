package com.teachermovies.http

import com.teachermovies.http.auth.InMemorySettingsRepository
import com.teachermovies.http.auth.PairingManager
import com.teachermovies.torrent.fake.FakeTorrentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.SecureRandom

class HttpServerControllerTest {
    private val settings = InMemorySettingsRepository()
    private val deps =
        ServerDeps(
            engine = FakeTorrentEngine(),
            space = { null },
            appVersion = "1.2.3",
            clock = { 0L },
            pairing = PairingManager(settings, SecureRandom(), { 0L }),
        )

    /** Records every start/stop the controller asks for, in order, as e.g. "start:8787". */
    private val calls = mutableListOf<String>()
    private var failOnPort: Int? = null

    private val serverFactory: (ServerDeps, Int) -> RunningServer = { _, port ->
        if (port == failOnPort) throw IllegalStateException("bind failed on $port")
        calls += "start:$port"
        RunningServer { calls += "stop:$port" }
    }

    private fun controller(scope: CoroutineScope) =
        HttpServerController(
            settings = settings,
            depsFactory = { deps },
            scope = scope,
            serverFactory = serverFactory,
        )

    @Test
    fun `start binds the configured port and reports Running`() =
        runTest {
            val controller = controller(this)

            controller.start()
            advanceUntilIdle()

            assertEquals(listOf("start:8787"), calls)
            assertEquals(ServerState.Running(8787), controller.state.value)
            controller.stop()
        }

    @Test
    fun `a port change stops the old server before starting the new one`() =
        runTest {
            val controller = controller(this)
            controller.start()
            advanceUntilIdle()

            settings.setHttpPort(9090)
            advanceUntilIdle()

            assertEquals(listOf("start:8787", "stop:8787", "start:9090"), calls)
            assertEquals(ServerState.Running(9090), controller.state.value)
            controller.stop()
        }

    @Test
    fun `a bind failure is reported as Failed and does not crash`() =
        runTest {
            failOnPort = 8787
            val controller = controller(this)

            controller.start()
            advanceUntilIdle()

            assertEquals(emptyList<String>(), calls)
            assertEquals(ServerState.Failed(8787, "bind failed on 8787"), controller.state.value)
            controller.stop()
        }

    @Test
    fun `stop stops the running server and reports Stopped`() =
        runTest {
            val controller = controller(this)
            controller.start()
            advanceUntilIdle()

            controller.stop()

            assertEquals(listOf("start:8787", "stop:8787"), calls)
            assertEquals(ServerState.Stopped, controller.state.value)
        }

    @Test
    fun `stop before start does nothing and start is idempotent`() =
        runTest {
            val controller = controller(this)

            controller.stop()
            assertEquals(ServerState.Stopped, controller.state.value)

            controller.start()
            controller.start()
            advanceUntilIdle()
            settings.setHttpPort(9090)
            advanceUntilIdle()

            // A second start() while already started must not add a second collector.
            assertEquals(listOf("start:8787", "stop:8787", "start:9090"), calls)
            controller.stop()
        }

    @Test
    fun `a change after a bind failure recovers on the new port`() =
        runTest {
            failOnPort = 8787
            val controller = controller(this)
            controller.start()
            advanceUntilIdle()
            assertEquals(ServerState.Failed(8787, "bind failed on 8787"), controller.state.value)

            failOnPort = null
            settings.setHttpPort(9090)
            advanceUntilIdle()

            assertEquals(listOf("start:9090"), calls)
            assertEquals(ServerState.Running(9090), controller.state.value)
            controller.stop()
        }
}
