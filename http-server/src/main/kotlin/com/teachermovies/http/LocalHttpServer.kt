package com.teachermovies.http

import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer

/**
 * The embedded HTTP server (ADR-0002: Ktor + CIO) serving [module] on [host]:[port].
 *
 * [start] returns immediately; [stop] shuts down gracefully and is idempotent. Refusing non-LAN
 * clients is #59; starting it on the TV is #65.
 */
class LocalHttpServer(
    private val deps: ServerDeps,
    private val port: Int,
    private val host: String = "0.0.0.0",
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /** Starts listening without blocking the caller. Does nothing while already running. */
    @Synchronized
    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = host) { module(deps) }.start(wait = false)
    }

    /** Stops the server, letting in-flight requests finish. Safe to call repeatedly or before [start]. */
    @Synchronized
    fun stop() {
        val running = server ?: return
        server = null
        running.stop(gracePeriodMillis = GRACE_PERIOD_MS, timeoutMillis = TIMEOUT_MS)
    }

    private companion object {
        const val GRACE_PERIOD_MS = 1_000L
        const val TIMEOUT_MS = 5_000L
    }
}
