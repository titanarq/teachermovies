package com.teachermovies.tv.ui.player

import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.teachermovies.core.model.TorrentId
import com.teachermovies.player.api.VideoSurfaceHost
import com.teachermovies.tv.player.AssistantAction
import com.teachermovies.tv.player.AssistantKeyMapper
import com.teachermovies.tv.player.PlayerUiState
import com.teachermovies.tv.player.PlayerViewModel
import com.teachermovies.tv.player.RemoteKeyMapper

/**
 * The `player/{id}` route (#78). The [PlayerViewModel] lives in a [ViewModelStore] of this route
 * alone, cleared when the route leaves composition, so every play gets a fresh session and one left
 * without BACK is still closed (position saved). [onExit] runs once the session is closed.
 */
@Composable
fun PlayerRoute(
    id: TorrentId,
    factory: ViewModelProvider.Factory,
    surfaceHost: VideoSurfaceHost,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val store = remember { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    val viewModel = remember(store) { ViewModelProvider(store, factory)[PlayerViewModel::class.java] }
    LaunchedEffect(id) { viewModel.open(id) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.exited) { if (state.exited) onExit() }
    PlayerScreen(
        state = state,
        surfaceHost = surfaceHost,
        onKey = { keyCode -> dispatchKey(keyCode, viewModel) },
        onAssistantAction = viewModel::onAssistantAction,
        onBack = viewModel::back,
        onSelectAudio = viewModel::selectAudio,
        onSelectSubtitle = viewModel::selectSubtitle,
        modifier = modifier,
    )
}

/**
 * Key dispatch on the player screen with the captured-line overlay closed (#86): the assistant
 * mapping goes first and the transport mapping of #78 only gets keys it leaves (null). Returns
 * whether the key was a player key.
 */
private fun dispatchKey(
    keyCode: Int,
    viewModel: PlayerViewModel,
): Boolean {
    val assistant = AssistantKeyMapper.map(keyCode, overlayOpen = false)
    if (assistant != null) {
        viewModel.onAssistantAction(assistant)
        return true
    }
    return RemoteKeyMapper.map(keyCode).also(viewModel::onAction) != null
}

/** Whether [keyCode] is a player key with the captured-line overlay closed (either mapping). */
private fun isPlayerKey(keyCode: Int): Boolean =
    AssistantKeyMapper.map(keyCode, overlayOpen = false) != null || RemoteKeyMapper.map(keyCode) != null

/**
 * Full-screen video with the transport overlay. The root box holds focus for the whole route, so
 * every remote key lands here: [onKey] gets each key-down code and returns whether it was a player
 * key (consumed, key-up included); other keys (volume, HOME) pass through. BACK is one of the
 * mapped keys; [BackHandler] is only the fallback should BACK arrive as a back gesture instead.
 *
 * While [PlayerUiState.tracksPanel] is open (#79) the root box maps no key, so the D-pad and OK
 * reach the panel's lists and BACK reaches [BackHandler] ([onBack] then closes the panel); focus
 * returns to the root box when the panel closes.
 *
 * While [PlayerUiState.assistant] is set (#86) [AssistantOverlay] holds focus and handles every key
 * but the volume keys (#178: neither it nor the root consumes them, so they reach the system)
 * through [onAssistantAction]; the transport overlay is hidden; focus returns to the root box when
 * it closes. [PlayerUiState.message] is shown in the transport overlay.
 */
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    surfaceHost: VideoSurfaceHost,
    onKey: (keyCode: Int) -> Boolean,
    onAssistantAction: (AssistantAction) -> Unit,
    onBack: () -> Unit,
    onSelectAudio: (String) -> Unit,
    onSelectSubtitle: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val panelOpen = state.tracksPanel != null
    val assistant = state.assistant
    val assistantOpen = assistant != null
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onBack)
    Box(
        modifier =
            modifier
                .background(Color.Black)
                .focusRequester(focus)
                .onKeyEvent { event ->
                    when {
                        panelOpen || assistantOpen -> false
                        event.type == KeyEventType.KeyDown -> onKey(event.key.nativeKeyCode)
                        // Swallow the key-up of a player key so nothing else reacts to it.
                        event.type == KeyEventType.KeyUp -> isPlayerKey(event.key.nativeKeyCode)
                        else -> false
                    }
                }.focusable(),
    ) {
        AndroidView(
            factory = { context -> FrameLayout(context).also(surfaceHost::attach) },
            modifier = Modifier.fillMaxSize(),
        )
        DisposableEffect(surfaceHost) { onDispose { surfaceHost.detach() } }

        val error = state.error
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(48.dp),
            )
        } else if (assistant != null) {
            AssistantOverlay(
                state = assistant,
                onAction = onAssistantAction,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else if (state.overlayVisible) {
            TransportOverlay(state = state, modifier = Modifier.align(Alignment.BottomCenter))
        }

        val panel = state.tracksPanel
        if (panel != null) {
            TracksPanel(
                state = panel,
                onSelectAudio = onSelectAudio,
                onSelectSubtitle = onSelectSubtitle,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
    LaunchedEffect(panelOpen, assistantOpen) { if (!panelOpen && !assistantOpen) focus.requestFocus() }
}

@Composable
private fun TransportOverlay(
    state: PlayerUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 48.dp, vertical = 24.dp),
    ) {
        Text(text = state.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
        state.message?.let { Text(text = it, style = MaterialTheme.typography.titleMedium, color = Color.White) }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.3f)),
        ) {
            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(state.progress).background(MaterialTheme.colorScheme.primary))
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(text = (if (state.isPlaying) "▶ " else "❚❚ ") + state.positionText, color = Color.White)
            Text(text = state.durationText, color = Color.White)
        }
    }
}
