package com.teachermovies.mobile.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.teachermovies.mobile.R

/** Asks for the PIN the chosen TV shows (#197). The typed PIN stays in this screen only. */
@Composable
fun PairingScreen(
    state: ConnectionUiState.Pairing,
    onSubmitPin: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pin by rememberSaveable(state.baseUrl) { mutableStateOf("") }
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = state.instanceName, style = MaterialTheme.typography.titleLarge)
        Text(text = stringResource(R.string.pin_prompt), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = pin,
            onValueChange = { typed -> pin = typed.filter { it.isDigit() }.take(PIN_DIGITS) },
            label = { Text(stringResource(R.string.pin_label)) },
            singleLine = true,
            enabled = !state.busy,
            isError = state.error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmitPin(pin) }),
        )
        state.error?.let { error ->
            Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (state.busy) CircularProgressIndicator()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            Button(onClick = { onSubmitPin(pin) }, enabled = !state.busy) {
                Text(stringResource(R.string.pair))
            }
        }
    }
}

private const val PIN_DIGITS = 6
