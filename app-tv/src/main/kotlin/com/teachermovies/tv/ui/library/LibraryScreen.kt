package com.teachermovies.tv.ui.library

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.teachermovies.core.model.TorrentId
import com.teachermovies.tv.R

/** The Biblioteca section bound to its [LibraryViewModel]; [onPlay] leaves with the chosen movie. */
@Composable
fun LibraryRoute(
    viewModel: LibraryViewModel,
    onPlay: (TorrentId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LibraryScreen(uiState = uiState, onPlay = onPlay, modifier = modifier)
}

/**
 * The stored movies as a grid of cards: title, `100 %` with a ▶ play mark, size and, when a resume
 * position is stored, `Continuar en ...`. OK on a card calls [onPlay] with its id.
 *
 * Focus: DOWN from the tab row enters on the first card (then on whichever card last had focus in
 * the grid); the D-pad walks the grid with Compose's own focus search; BACK is the shell's (focus
 * returns to the Biblioteca tab). With no movies the empty-state text holds nothing focusable, so
 * focus stays in the tab row.
 */
@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    onPlay: (TorrentId) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (uiState.items.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(text = stringResource(R.string.library_empty))
        }
        return
    }

    val firstCard = remember { FocusRequester() }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 240.dp),
        modifier = modifier.focusRestorer(firstCard).focusGroup(),
        contentPadding = PaddingValues(48.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        itemsIndexed(uiState.items, key = { _, card -> card.id.value }) { index, card ->
            LibraryCardItem(
                card = card,
                onClick = { onPlay(card.id) },
                modifier = if (index == 0) Modifier.focusRequester(firstCard) else Modifier,
            )
        }
    }
}

@Composable
private fun LibraryCardItem(
    card: LibraryCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = card.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = stringResource(R.string.library_play_mark), style = MaterialTheme.typography.titleMedium)
                Text(text = stringResource(R.string.library_complete))
                Text(text = card.sizeText, style = MaterialTheme.typography.bodySmall)
            }
            card.resumeText?.let { resume ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = resume, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
