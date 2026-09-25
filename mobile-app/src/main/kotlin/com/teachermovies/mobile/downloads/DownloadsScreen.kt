package com.teachermovies.mobile.downloads

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.teachermovies.mobile.api.TorrentSummary
import kotlinx.coroutines.delay

/**
 * The paired TV's downloads (#198), the phone side of the web UX in `docs/VISION.md` ("UX móvil"):
 * the TV's name and whether it is answering, the field a magnet is pasted into, the list the TV last
 * reported and the way to forget the TV. Pause, resume and delete stay on the web UI.
 *
 * Every number of a row is built by [DownloadFormat], so the screen translates nothing itself. The
 * typed magnet stays in this screen's field; [onNoticeShown] tells [DownloadsViewModel] that the
 * notice it exposed has been read, which is what keeps it transient.
 */
@Composable
fun DownloadsScreen(
    state: DownloadsUiState,
    instanceName: String,
    onSend: (String) -> Unit,
    onNoticeShown: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var magnet by rememberSaveable { mutableStateOf("") }
    val notice = state.notice
    val offline = state is DownloadsUiState.Loaded && state.offline
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MILLIS)
            onNoticeShown()
        }
    }
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(if (offline) R.string.tv_offline else R.string.tv_connected, instanceName),
            style = MaterialTheme.typography.titleLarge,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = magnet,
                onValueChange = { typed -> magnet = typed },
                label = { Text(stringResource(R.string.magnet_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend(magnet) }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { onSend(magnet) }) { Text(stringResource(R.string.send_to_tv)) }
        }
        notice?.let { message ->
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
        Text(text = stringResource(R.string.downloads_header), style = MaterialTheme.typography.labelLarge)
        when (state) {
            is DownloadsUiState.Loading -> {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            is DownloadsUiState.Loaded -> {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(state.items, key = { it.id }) { torrent ->
                        DownloadRow(torrent)
                        HorizontalDivider()
                    }
                }
            }
        }
        OutlinedButton(onClick = onForget, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.forget_tv))
        }
    }
}

/** One download: its name, how far along it is and the four texts [DownloadFormat] builds. */
@Composable
private fun DownloadRow(torrent: TorrentSummary) {
    ListItem(
        headlineContent = { Text(torrent.name) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(
                    progress = { (torrent.progress / WHOLE_PROGRESS).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(rowSummary(torrent))
            }
        },
    )
}

private fun rowSummary(torrent: TorrentSummary): String =
    listOf(
        DownloadFormat.progress(torrent.progress),
        DownloadFormat.stateLabel(torrent.state),
        DownloadFormat.speed(torrent.downloadSpeed),
        DownloadFormat.size(torrent.downloadedBytes, torrent.totalBytes),
    ).joinToString(separator = ROW_SEPARATOR)

private const val NOTICE_MILLIS = 3_000L

// The TV reports progress as 0..100; the indicator wants a 0..1 fraction of the row's width.
private const val WHOLE_PROGRESS = 100.0

private const val ROW_SEPARATOR = "  ·  "
