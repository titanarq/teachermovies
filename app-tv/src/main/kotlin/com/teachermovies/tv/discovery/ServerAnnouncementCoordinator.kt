package com.teachermovies.tv.discovery

import com.teachermovies.discovery.ServiceAnnouncer
import com.teachermovies.discovery.ServiceNames
import com.teachermovies.discovery.TvServiceInfo
import com.teachermovies.http.ServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Keeps the LAN announcement in step with the HTTP server (#103): announces the server's port
 * while it is [ServerState.Running] and withdraws the announcement when it is
 * [ServerState.Stopped] or [ServerState.Failed].
 *
 * The announcement is never on the server's path: [ServiceAnnouncer] does not throw and reports
 * failures only on its own state, which nothing here (or in the UI) reads. The device name
 * arrives through [deviceName] so this class stays free of Android framework types.
 */
class ServerAnnouncementCoordinator(
    private val announcer: ServiceAnnouncer,
    private val serverState: StateFlow<ServerState>,
    private val deviceName: () -> String?,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    /** The port currently announced, or null when nothing is announced. */
    private var announcedPort: Int? = null

    /** Starts following [serverState]; a no-op if already started. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { serverState.collect(::onServerState) }
    }

    /** Stops following [serverState] and withdraws the announcement. */
    fun stop() {
        job?.cancel()
        job = null
        announcedPort = null
        announcer.stop()
    }

    private fun onServerState(state: ServerState) {
        when (state) {
            is ServerState.Running -> {
                if (announcedPort == state.port) return
                announcedPort = state.port
                announcer.announce(
                    TvServiceInfo(
                        instanceName = ServiceNames.instanceName(deviceName()),
                        port = state.port,
                    ),
                )
            }
            ServerState.Stopped, is ServerState.Failed -> {
                announcedPort = null
                announcer.stop()
            }
        }
    }
}
