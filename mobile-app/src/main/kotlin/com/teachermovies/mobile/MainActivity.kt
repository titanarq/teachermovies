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
import com.teachermovies.mobile.connection.ConnectedScreen
import com.teachermovies.mobile.connection.ConnectionUiState
import com.teachermovies.mobile.connection.ConnectionViewModel
import com.teachermovies.mobile.connection.PairingScreen
import com.teachermovies.mobile.connection.TvListScreen

/** Launcher activity: hosts the connection screens driven by [ConnectionViewModel] (#197). */
class MainActivity : ComponentActivity() {
    private val viewModel: ConnectionViewModel by viewModels {
        val container = (application as MobileApp).container
        ConnectionViewModel.Factory(
            discoverer = container.serviceDiscoverer,
            api = container.tvApi,
            store = container.pairedTvStore,
            deviceName = container.deviceName,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                ConnectionScreen(state, viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionScreen(
    state: ConnectionUiState,
    viewModel: ConnectionViewModel,
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
                    onSelectTv = viewModel::selectTv,
                    onEnterAddress = viewModel::enterAddress,
                    modifier = modifier,
                )
            }

            is ConnectionUiState.Pairing -> {
                BackHandler(onBack = viewModel::cancelPairing)
                PairingScreen(
                    state = state,
                    onSubmitPin = viewModel::submitPin,
                    onCancel = viewModel::cancelPairing,
                    modifier = modifier,
                )
            }

            is ConnectionUiState.Connected -> {
                ConnectedScreen(state = state, onForget = viewModel::forgetTv, modifier = modifier)
            }
        }
    }
}
