package com.teachermovies.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teachermovies.mobile.connection.ConnectionUiState
import com.teachermovies.mobile.connection.ConnectionViewModel
import com.teachermovies.mobile.connection.PairingScreen
import com.teachermovies.mobile.connection.TvListScreen
import com.teachermovies.mobile.downloads.DownloadsScreen
import com.teachermovies.mobile.downloads.DownloadsViewModel

/**
 * Launcher activity: hosts the connection screens driven by [ConnectionViewModel] (#197) and, once
 * paired, the downloads driven by [DownloadsViewModel] (#198). Both are built from the one
 * `MobileContainer`.
 */
class MainActivity : ComponentActivity() {
    private val connectionViewModel: ConnectionViewModel by viewModels {
        val container = (application as MobileApp).container
        ConnectionViewModel.Factory(
            discoverer = container.serviceDiscoverer,
            api = container.tvApi,
            store = container.pairedTvStore,
            deviceName = container.deviceName,
        )
    }

    private val downloadsViewModel: DownloadsViewModel by viewModels {
        val container = (application as MobileApp).container
        DownloadsViewModel.Factory(
            api = container.tvApi,
            store = container.pairedTvStore,
            magnetSender = container.magnetSender,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val state by connectionViewModel.uiState.collectAsStateWithLifecycle()
                ConnectionScreen(state, connectionViewModel, downloadsViewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionScreen(
    state: ConnectionUiState,
    connectionViewModel: ConnectionViewModel,
    downloadsViewModel: DownloadsViewModel,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (state) {
            ConnectionUiState.Loading -> {
                Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            is ConnectionUiState.Searching -> {
                TvListScreen(
                    state = state,
                    onSelectTv = connectionViewModel::selectTv,
                    onEnterAddress = connectionViewModel::enterAddress,
                    modifier = modifier,
                )
            }

            is ConnectionUiState.Pairing -> {
                BackHandler(onBack = connectionViewModel::cancelPairing)
                PairingScreen(
                    state = state,
                    onSubmitPin = connectionViewModel::submitPin,
                    onCancel = connectionViewModel::cancelPairing,
                    modifier = modifier,
                )
            }

            is ConnectionUiState.Connected -> {
                // Collected only while a TV is paired: what the ViewModel is subscribed to is what
                // decides whether the TV is polled at all.
                val downloads by downloadsViewModel.uiState.collectAsStateWithLifecycle()
                DownloadsScreen(
                    state = downloads,
                    instanceName = state.pairedTv.instanceName,
                    onSend = downloadsViewModel::send,
                    onNoticeShown = downloadsViewModel::noticeShown,
                    onForget = connectionViewModel::forgetTv,
                    modifier = modifier,
                )
            }
        }
    }
}
