package com.teachermovies.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Text
import com.teachermovies.tv.R
import com.teachermovies.tv.player.TrackOption
import com.teachermovies.tv.player.TracksPanelState

/**
 * The audio/subtitle side panel (#79): two focusable lists side by side, `Audio` and `Subtítulos`
 * (first row `Desactivados`), the active track of each marked. UP/DOWN move inside a list,
 * LEFT/RIGHT between them (entering on the marked row), and focus never leaves the panel while it
 * is open. Opening puts focus on the marked audio row (the marked subtitle row when the media has
 * no audio track). OK on a row selects it; BACK is handled by the screen's back handler.
 */
@Composable
fun TracksPanel(
    state: TracksPanelState,
    onSelectAudio: (String) -> Unit,
    onSelectSubtitle: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val audioRequester = remember { FocusRequester() }
    val subtitleRequester = remember { FocusRequester() }
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        modifier =
            modifier
                .fillMaxHeight()
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(horizontal = 32.dp, vertical = 48.dp)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
    ) {
        TrackList(
            title = stringResource(R.string.player_tracks_audio),
            options = state.audio,
            emptyText = stringResource(R.string.player_tracks_no_audio),
            requester = audioRequester,
            onSelect = { id -> id?.let(onSelectAudio) },
        )
        TrackList(
            title = stringResource(R.string.player_tracks_subtitles),
            options = state.subtitles,
            emptyText = null,
            requester = subtitleRequester,
            onSelect = onSelectSubtitle,
        )
    }
    LaunchedEffect(Unit) {
        if (state.audio.isNotEmpty()) audioRequester.requestFocus() else subtitleRequester.requestFocus()
    }
}

/**
 * One column of the panel. [requester] is attached to the marked row (the first row when none is
 * marked) and doubles as the column's focus-restore target.
 */
@Composable
private fun TrackList(
    title: String,
    options: List<TrackOption>,
    emptyText: String?,
    requester: FocusRequester,
    onSelect: (String?) -> Unit,
) {
    val entryIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .width(340.dp)
                .focusRestorer(requester)
                .focusGroup()
                .verticalScroll(rememberScrollState()),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge, color = Color.White)
        if (options.isEmpty() && emptyText != null) {
            Text(text = emptyText, color = Color.White.copy(alpha = 0.7f))
        }
        options.forEachIndexed { index, option ->
            ListItem(
                selected = option.selected,
                onClick = { onSelect(option.id) },
                headlineContent = { Text(text = option.label) },
                leadingContent = { RadioButton(selected = option.selected, onClick = null) },
                modifier = if (index == entryIndex) Modifier.focusRequester(requester) else Modifier,
            )
        }
    }
}
