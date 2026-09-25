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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
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

/**
 * The Biblioteca section bound to its [LibraryViewModel]; [onPlay] leaves with the chosen movie.
 * [restoreFocusTo] / [onFocusRestored] carry the card to refocus after the player (#249).
 */
@Composable
fun LibraryRoute(
    viewModel: LibraryViewModel,
    onPlay: (TorrentId) -> Unit,
    modifier: Modifier = Modifier,
    restoreFocusTo: TorrentId? = null,
    onFocusRestored: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LibraryScreen(
        uiState = uiState,
        onPlay = onPlay,
        modifier = modifier,
        restoreFocusTo = restoreFocusTo,
        onFocusRestored = onFocusRestored,
    )
}

/**
 * Index in [items] of the card [id] should put focus back on, or null when there is nothing to
 * restore ([id] null, or that movie is no longer in the library).
 */
internal fun restoreFocusIndex(
    items: List<LibraryCard>,
    id: TorrentId?,
): Int? = id?.let { target -> items.indexOfFirst { it.id == target }.takeIf { it >= 0 } }

/**
 * The stored movies as a grid of cards: title, `100 %` with a ▶ play mark, size and, when a resume
 * position is stored, `Continuar en ...`. OK on a card calls [onPlay] with its id.
 *
 * Focus: DOWN from the tab row enters on the first card (then on whichever card last had focus in
 * the grid); the D-pad walks the grid with Compose's own focus search; BACK is the shell's (focus
 * returns to the Biblioteca tab). With no movies the empty-state text holds nothing focusable, so
 * focus stays in the tab row.
 *
 * Coming back from the player (#249) the shell passes the played movie as [restoreFocusTo]: the
 * grid scrolls to its card and that card takes focus (after the shell's own launch request on the
 * tab, so it wins), then [onFocusRestored] is called. If the movie is gone the request is dropped
 * and focus stays on the tab.
 */
@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    onPlay: (TorrentId) -> Unit,
    modifier: Modifier = Modifier,
    restoreFocusTo: TorrentId? = null,
    onFocusRestored: () -> Unit = {},
) {
    if (uiState.items.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(text = stringResource(R.string.library_empty))
        }
        return
    }

    val firstCard = remember { FocusRequester() }
    val restoredCard = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    val restoreIndex = restoreFocusIndex(uiState.items, restoreFocusTo)
    LaunchedEffect(restoreFocusTo, restoreIndex) {
        if (restoreFocusTo == null) return@LaunchedEffect
        if (restoreIndex != null) {
            // The card may be off screen (not composed yet): bring it in, let it lay out, focus it.
            gridState.scrollToItem(restoreIndex)
            withFrameNanos { }
            restoredCard.requestFocus()
        }
        onFocusRestored()
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 240.dp),
        state = gridState,
        modifier = modifier.focusRestorer(firstCard).focusGroup(),
        contentPadding = PaddingValues(48.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        itemsIndexed(uiState.items, key = { _, card -> card.id.value }) { index, card ->
            LibraryCardItem(
                card = card,
                onClick = { onPlay(card.id) },
                modifier =
                    Modifier
                        .then(if (index == 0) Modifier.focusRequester(firstCard) else Modifier)
                        .then(if (index == restoreIndex) Modifier.focusRequester(restoredCard) else Modifier),
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
