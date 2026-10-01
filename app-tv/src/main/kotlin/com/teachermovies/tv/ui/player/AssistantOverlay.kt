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
import com.teachermovies.assistant.TranslationFailure
import com.teachermovies.assistant.TranslationUiState
import com.teachermovies.assistant.explanation.Explanation
import com.teachermovies.assistant.explanation.ExplanationUiState
import com.teachermovies.tv.player.AssistantAction
import com.teachermovies.tv.player.AssistantKeyMapper
import com.teachermovies.tv.player.AssistantOverlayState

/** The hint line under the captured text (#86, #293; ADR-0005 §7); [hintLine] says when it is replaced. */
const val ASSISTANT_HINT = "ARRIBA Explicar · ATRÁS Cerrar"

/** Shown while the captured fragment is replaying (#86). */
const val ASSISTANT_REPLAYING = "Repitiendo…"

/** Shown while a spoken answer is being said (#92). */
const val ASSISTANT_SPEAKING = "Hablando…"

/** Translation states drawn under the English line (#92). */
const val TRANSLATION_LOADING = "Traduciendo…"
const val TRANSLATION_OFFLINE = "Sin conexión para traducir"
const val TRANSLATION_UNAVAILABLE = "Traducción no disponible"

/** RIGHT's explanation states (#293). */
const val EXPLANATION_THINKING = "Pensando…"
const val EXPLANATION_UNAVAILABLE = "Explicación no disponible (enciende el portátil)"

/**
 * The lines the overlay draws for [explanation] (#293), top to bottom: none while
 * [ExplanationUiState.Idle], [EXPLANATION_THINKING] while the request is on its way, the summary, up
 * to [Explanation.MAX_POINTS] points as `expression: explanation` and the optional subtitle note once
 * shown, or [EXPLANATION_UNAVAILABLE] on any failure.
 */
fun explanationLines(explanation: ExplanationUiState): List<String> =
    when (explanation) {
        ExplanationUiState.Idle -> {
            emptyList()
        }

        is ExplanationUiState.Thinking -> {
            listOf(EXPLANATION_THINKING)
        }

        is ExplanationUiState.Shown -> {
            val shown = explanation.explanation
            listOf(shown.summary) +
                shown.points.take(Explanation.MAX_POINTS).map { "${it.expression}: ${it.explanation}" } +
                listOfNotNull(shown.subtitleNote)
        }

        is ExplanationUiState.Unavailable -> {
            listOf(EXPLANATION_UNAVAILABLE)
        }
    }

/**
 * What the overlay draws under the English line for [translation]: null while
 * [TranslationUiState.Idle] (nothing), the Spanish text when `Ready`, or a status line.
 */
fun translationLine(translation: TranslationUiState): String? =
    when (translation) {
        TranslationUiState.Idle -> {
            null
        }

        TranslationUiState.Loading -> {
            TRANSLATION_LOADING
        }

        is TranslationUiState.Ready -> {
            translation.text
        }

        is TranslationUiState.Failed -> {
            when (translation.reason) {
                TranslationFailure.OFFLINE -> TRANSLATION_OFFLINE
                TranslationFailure.UNAVAILABLE -> TRANSLATION_UNAVAILABLE
            }
        }
    }

/**
 * The line the overlay draws at the bottom: [ASSISTANT_HINT] while the replay target is the captured
 * line itself, or how many lines back it is once OK pressed in a row has stepped back through the
 * track (#339).
 */
fun hintLine(linesBack: Int): String = if (linesBack <= 0) ASSISTANT_HINT else "‹ $linesBack frases atrás"

/**
 * The captured-line overlay (#86): a dimmed band at the bottom of the video with the English
 * [AssistantOverlayState.text] in a large size, the hint line ([hintLine]) and a replaying
 * indicator. Under the English line (#92) it draws the Spanish translation or its status ([translationLine]) with its
 * [AssistantOverlayState.spanishLabel] ("subtítulo" or "IA", #288), RIGHT's explanation or its status
 * ([explanationLines], #293) and a discreet indicator while [AssistantOverlayState.speaking].
 *
 * It takes focus as soon as it appears and maps every key-down through
 * [AssistantKeyMapper.map] with the overlay open, so OK/ENTER/PLAY_PAUSE replay, LEFT/RIGHT rewind
 * (#347), BACK/DOWN/CAPTIONS dismiss, and every other key is swallowed ([AssistantAction.Consumed]) -- key-ups included -- so
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
                    // Open overlay: every key is its own except the volume keys (mapper null, #178),
                    // which are not consumed (key-down and key-up) so the system changes the volume.
                    val action = AssistantKeyMapper.map(event.key.nativeKeyCode, overlayOpen = true)
                    if (action != null && event.type == KeyEventType.KeyDown) onAction(action)
                    action != null
                }.focusable(),
    ) {
        Text(
            text = state.text,
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp, lineHeight = 52.sp),
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        translationLine(state.translation)?.let { line ->
            val ready = state.translation is TranslationUiState.Ready
            Text(
                text = line,
                style =
                    if (ready) {
                        MaterialTheme.typography.headlineMedium.copy(fontSize = 32.sp, lineHeight = 42.sp)
                    } else {
                        MaterialTheme.typography.titleLarge
                    },
                color = if (ready) Color(0xFFFFE082) else Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Where the Spanish line came from (#288): the aligned subtitle or the AI.
        state.spanishLabel?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // RIGHT's explanation (#293): the summary stands out, points and note follow smaller.
        val shown = state.explanation is ExplanationUiState.Shown
        explanationLines(state.explanation).forEachIndexed { index, line ->
            Text(
                text = line,
                style =
                    if (shown && index == 0) {
                        MaterialTheme.typography.titleLarge
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                color = if (shown) Color(0xFFB3E5FC) else Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.speaking) {
            Text(
                text = ASSISTANT_SPEAKING,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
            text = hintLine(state.linesBack),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
