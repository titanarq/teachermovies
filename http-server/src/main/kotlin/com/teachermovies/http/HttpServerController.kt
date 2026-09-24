package com.teachermovies.http

import com.teachermovies.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * A server instance started by a [HttpServerController]'s server factory.
 *
 * The real implementation wraps [LocalHttpServer]; a fake in tests never opens a socket.
 */
fun interface RunningServer {
    /** Stops the server. Called at most once per instance, from the controller's [stop] or a restart. */
    fun stop()
}

/** Lifecycle state of the embedded HTTP server, as reported by [HttpServerController.state]. */
sealed interface ServerState {
    /** Not running: before the first [HttpServerController.start], or after [HttpServerController.stop]. */
    data object Stopped : ServerState

    /** Listening on [port]. */
    data class Running(val port: Int) : ServerState

    /** The server failed to start on [port]; [reason] is the exception message, never a stack trace. */
    data class Failed(val port: Int, val reason: String) : ServerState
}

/**
 * Runs the embedded HTTP server for as long as the app is alive, on the port [SettingsRepository]
 * holds, moving it to a new port whenever that setting changes, and reporting [state].
 *
 * [depsFactory] is called for every (re)start, so [ServerDeps] can pick up dependencies created or
 * changed after this controller was built. [serverFactory] builds and starts the actual server on
 * the given port (real: an adapter over [LocalHttpServer]; tests: a fake that never opens a
 * socket). A bind failure is reported as [ServerState.Failed] and never crashes the app.
 *
 * Hosting this in the app/service and showing the pairing PIN is #66; this class only owns the
 * port-follows-settings restart logic.
 */
class HttpServerController(
    private val settings: SettingsRepository,
    private val depsFactory: () -> ServerDeps,
    private val scope: CoroutineScope,
    private val serverFactory: (ServerDeps, Int) -> RunningServer,
) {
    private val mutableState = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = mutableState

    private var running: RunningServer? = null
    private var collectJob: Job? = null

    /**
     * Starts following the configured port: starts the server on the first value, and on every
     * later change stops the running server (if any) before starting the new one. Does nothing
     * while already started.
     */
    fun start() {
        if (collectJob != null) return
        collectJob =
            settings.settings
                .map { it.httpPort }
                .distinctUntilChanged()
                .onEach(::restart)
                .launchIn(scope)
    }

    /** Stops the server and stops following the setting. Safe to call repeatedly or before [start]. */
    fun stop() {
        collectJob?.cancel()
        collectJob = null
        stopRunning()
        mutableState.value = ServerState.Stopped
    }

    private fun restart(port: Int) {
        stopRunning()
        try {
            running = serverFactory(depsFactory(), port)
            mutableState.value = ServerState.Running(port)
        } catch (e: Exception) {
            mutableState.value = ServerState.Failed(port, e.message ?: e::class.simpleName ?: "unknown error")
        }
    }

    private fun stopRunning() {
        running?.stop()
        running = null
    }
}
