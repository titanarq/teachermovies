package com.teachermovies.tv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import com.teachermovies.core.model.TorrentId
import com.teachermovies.tv.R

/**
 * What the `player/{id}` route shows until the real player lands (#78): `Reproductor pendiente`.
 * The box takes focus so no D-pad key reaches the shell it replaced; BACK calls [onBack].
 */
@Composable
fun PlayerPlaceholderScreen(
    @Suppress("UNUSED_PARAMETER") id: TorrentId,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onBack)
    Box(
        modifier = modifier.focusRequester(focus).focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = stringResource(R.string.player_pending))
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
