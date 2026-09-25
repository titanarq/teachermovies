package com.teachermovies.mobile.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.teachermovies.mobile.R

/** Placeholder while paired (#197); the downloads list and sending a magnet come later (#35). */
@Composable
fun ConnectedScreen(
    state: ConnectionUiState.Connected,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.tv_connected, state.pairedTv.instanceName),
            style = MaterialTheme.typography.titleLarge,
        )
        OutlinedButton(onClick = onForget) { Text(stringResource(R.string.forget_tv)) }
    }
}
