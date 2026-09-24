package com.teachermovies.tv.ui.firstrun

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.teachermovies.torrent.api.EngineStatus
import com.teachermovies.tv.R
import com.teachermovies.tv.ui.server.ServerPinAndStatus
import com.teachermovies.tv.ui.settings.SpaceFormat

/**
 * The first-run screen bound to its [FirstRunViewModel]. Each time it is shown the LAN address and
 * the volume list are re-read, so a network or USB drive connected meanwhile appears.
 */
@Composable
fun FirstRunRoute(viewModel: FirstRunViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    FirstRunScreen(uiState = uiState, onContinue = viewModel::complete, modifier = modifier)
}

/**
 * The address a phone opens and the PIN it pairs with (plus the server's state when it is not
 * running), where downloads go and how much room is left there, the torrent
 * engine's state, and a `Continuar` button to the shell.
 *
 * Focus: `Continuar` is the only focusable element and takes focus as soon as the screen appears,
 * so OK on the remote continues right away. BACK is not intercepted: it leaves the app, and the
 * first-run screen shows again on the next launch until `Continuar` is pressed.
 */
@Composable
fun FirstRunScreen(
    uiState: FirstRunUiState,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val continueRequester = remember { FocusRequester() }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 64.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.first_run_title), style = MaterialTheme.typography.headlineLarge)
        Text(
            text = stringResource(R.string.first_run_hint),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.widthIn(max = 720.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.first_run_server, uiState.serverUrl ?: stringResource(R.string.first_run_no_network)),
            style = MaterialTheme.typography.headlineMedium,
        )
        ServerPinAndStatus(pin = uiState.pin, serverState = uiState.serverState, style = MaterialTheme.typography.headlineMedium)
        Text(
            text =
                uiState.freeBytes?.let { stringResource(R.string.first_run_free_space, SpaceFormat.freeGb(it)) }
                    ?: stringResource(R.string.first_run_free_space_unknown),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text =
                stringResource(
                    R.string.first_run_download_folder,
                    uiState.downloadFolder ?: stringResource(R.string.first_run_no_volume),
                ),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.first_run_engine, stringResource(uiState.engineStatus.labelRes)),
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onContinue, modifier = Modifier.focusRequester(continueRequester)) {
            Text(text = stringResource(R.string.first_run_continue))
        }
    }

    LaunchedEffect(Unit) { continueRequester.requestFocus() }
}

private val EngineStatus.labelRes: Int
    get() =
        when (this) {
            EngineStatus.Stopped -> R.string.engine_status_stopped
            EngineStatus.Starting -> R.string.engine_status_starting
            EngineStatus.Running -> R.string.engine_status_running
            EngineStatus.Error -> R.string.engine_status_error
        }
