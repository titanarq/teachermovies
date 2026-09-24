package com.teachermovies.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.teachermovies.tv.player.AssistantAction
import com.teachermovies.tv.player.AssistantKeyMapper
import com.teachermovies.tv.player.AssistantOverlayState

/** The hint line under the captured text (#86). */
const val ASSISTANT_HINT = "OK Repetir · ATRÁS Cerrar"

/** Shown while the captured fragment is replaying (#86). */
const val ASSISTANT_REPLAYING = "Repitiendo…"

/**
 * The captured-line overlay (#86): a dimmed band at the bottom of the video with the English
 * [AssistantOverlayState.text] in a large size, the hint line and a replaying indicator.
 *
 * It takes focus as soon as it appears and maps every key-down through
 * [AssistantKeyMapper.map] with the overlay open, so OK/ENTER/PLAY_PAUSE replay, BACK/DOWN/CAPTIONS
 * dismiss, and every other key is swallowed ([AssistantAction.Consumed]) -- key-ups included -- so
 * nothing behind it reacts. When it leaves composition the player screen takes focus back.
 */
@Composable
fun AssistantOverlay(
    state: AssistantOverlayState,
    onAction: (AssistantAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(horizontal = 64.dp, vertical = 32.dp)
                .focusRequester(focus)
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        AssistantKeyMapper.map(event.key.nativeKeyCode, overlayOpen = true)?.let(onAction)
                    }
                    // Open overlay: every key is its own (mapper never returns null here).
                    true
                }.focusable(),
    ) {
        Text(
            text = state.text,
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp, lineHeight = 52.sp),
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.replaying) {
            Text(
                text = ASSISTANT_REPLAYING,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = ASSISTANT_HINT,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
