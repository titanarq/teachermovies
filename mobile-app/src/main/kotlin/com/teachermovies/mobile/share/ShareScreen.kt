package com.teachermovies.mobile.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.teachermovies.mobile.R

/**
 * The share dialog (#199): one card over the app the magnet was shared from, reporting what came of
 * the one send `ShareViewModel` started. There is nothing here to drive -- `Enviando a la TV...`
 * while the send is in flight and then the outcome's own `SendOutcome.message`, `CERRAR` in both
 * states because a dialog that cannot be dismissed is a dialog that can trap the user over an
 * unreachable TV, and `ABRIR MOVIE ASSISTANT` only while [ShareUiState.offersOpenApp] says that
 * opening the app is the one thing that can fix it. The card sizes to its content, which is what
 * the activity's dialog window wraps.
 */
@Composable
fun ShareScreen(
    state: ShareUiState,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state) {
                ShareUiState.Sending -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.share_sending),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }

                is ShareUiState.Done -> {
                    Text(text = state.outcome.message, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.offersOpenApp) {
                    OutlinedButton(onClick = onOpenApp) { Text(stringResource(R.string.share_open_app)) }
                }
                Button(onClick = onClose) { Text(stringResource(R.string.share_close)) }
            }
        }
    }
}
