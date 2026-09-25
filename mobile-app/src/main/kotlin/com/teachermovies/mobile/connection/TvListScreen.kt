package com.teachermovies.mobile.connection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.teachermovies.discovery.client.DiscoveredTv
import com.teachermovies.mobile.R

/** The discovered TVs, one row each, plus a typed-address fallback (#197). */
@Composable
fun TvListScreen(
    state: ConnectionUiState.Searching,
    onSelectTv: (DiscoveredTv) -> Unit,
    onEnterAddress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var address by rememberSaveable { mutableStateOf("") }
    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.tvs.isEmpty()) {
                Text(
                    text = stringResource(R.string.searching_tv),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.tvs, key = { it.instanceName }) { tv ->
                        ListItem(
                            headlineContent = { Text(tv.instanceName) },
                            supportingContent = { Text("${tv.host}:${tv.port}") },
                            modifier = Modifier.clickable { onSelectTv(tv) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text(stringResource(R.string.enter_address)) },
                singleLine = true,
                isError = state.addressError != null,
                supportingText = state.addressError?.let { error -> { Text(error) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onEnterAddress(address) }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { onEnterAddress(address) }) {
                Text(stringResource(R.string.connect))
            }
        }
    }
}
