package com.teachermovies.tv.ui.server

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.teachermovies.http.ServerState
import com.teachermovies.tv.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * What the TV says about the HTTP server when it is not simply running (#66): nothing while it
 * runs, `Servidor detenido` while stopped, `Error: puerto en uso` when the port could not be bound
 * and `Error: <reason>` for any other start failure.
 */
sealed interface ServerStatusLabel {
    data object Running : ServerStatusLabel

    data object Stopped : ServerStatusLabel

    data object PortInUse : ServerStatusLabel

    data class Failed(val reason: String) : ServerStatusLabel

    companion object {
        /** Pure mapping, so it is covered by JVM tests without Compose. */
        fun of(state: ServerState): ServerStatusLabel =
            when (state) {
                is ServerState.Running -> Running
                ServerState.Stopped -> Stopped
                is ServerState.Failed -> if (isPortInUse(state.reason)) PortInUse else Failed(state.reason)
            }

        // `java.net.BindException: Address already in use` on the JVM and on Android (EADDRINUSE).
        private fun isPortInUse(reason: String): Boolean =
            reason.contains("in use", ignoreCase = true) || reason.contains("EADDRINUSE", ignoreCase = true)
    }
}

/** How often a shown PIN is re-read, so the screen follows `PairingManager`'s 10-minute rotation. */
const val PIN_REFRESH_INTERVAL_MS: Long = 15_000L

/** Emits every [intervalMs], forever; the ViewModels' default PIN refresh trigger. */
fun pinRefreshTicker(intervalMs: Long = PIN_REFRESH_INTERVAL_MS): Flow<Unit> =
    flow {
        while (true) {
            delay(intervalMs)
            emit(Unit)
        }
    }

/** `PIN 482916`, plus the server status line when the server is not running. Nothing focusable. */
@Composable
fun ServerPinAndStatus(
    pin: String,
    serverState: ServerState,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    if (pin.isNotEmpty()) {
        Text(text = stringResource(R.string.server_pin, pin), style = style)
    }
    val status =
        when (val label = ServerStatusLabel.of(serverState)) {
            ServerStatusLabel.Running -> null
            ServerStatusLabel.Stopped -> stringResource(R.string.server_stopped)
            ServerStatusLabel.PortInUse -> stringResource(R.string.server_error_port_in_use)
            is ServerStatusLabel.Failed -> stringResource(R.string.server_error, label.reason)
        }
    if (status != null) {
        Text(text = status, style = style, color = MaterialTheme.colorScheme.error)
    }
}
