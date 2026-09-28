package com.teachermovies.assistant.explanation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the assistant panel shows for "Explicar" (#293 renders it). */
sealed interface ExplanationUiState {
    /** Nothing asked, or the last request was dismissed. */
    data object Idle : ExplanationUiState

    /** The explanation of [line] is on its way ("Pensando…"). */
    data class Thinking(
        val line: String,
    ) : ExplanationUiState

    /** The explanation of [line]: its summary, up to three points and the optional subtitle note. */
    data class Shown(
        val line: String,
        val explanation: Explanation,
        val fromCache: Boolean,
    ) : ExplanationUiState

    /**
     * No explanation of [line] ("Explicación no disponible (enciende el portátil)"). [retryable] is
     * true when the laptop may answer next time (timed out, dropped), false when asking again now
     * would answer the same (no bridge, a bridge error, an unusable reply); [reason] is for logs only.
     */
    data class Unavailable(
        val line: String,
        val retryable: Boolean,
        val reason: String,
    ) : ExplanationUiState
}

/**
 * Drives [ExplanationUiState] for the assistant panel over a [LineExplainer] (ADR-0005 §7).
 *
 * At most one explanation request is ever in flight: [explain] cancels the one before it and the new
 * request reaches the bridge only once the old one has finished cancelling, and a result that arrives
 * for a line the learner has moved on from is dropped, never shown. Asking for the line that is
 * already on its way again changes nothing. [dismiss] cancels and returns to
 * [ExplanationUiState.Idle].
 *
 * Only an explicit [explain] asks anything -- no line is explained ahead of the learner (no prefetch)
 * -- and nothing is spoken: this controller has no speech dependency at all (no TTS).
 */
class ExplanationController(
    private val explainer: LineExplainer,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<ExplanationUiState>(ExplanationUiState.Idle)

    val state: StateFlow<ExplanationUiState> = mutableState.asStateFlow()

    private val lock = Any()
    private var generation = 0L
    private var inFlight: Job? = null
    private var inFlightContext: ExplanationContext? = null

    /** Explains [context], replacing whatever was being explained. */
    fun explain(context: ExplanationContext) {
        synchronized(lock) {
            if (context == inFlightContext && inFlight?.isActive == true) return
            val previous = inFlight
            previous?.cancel()
            val mine = ++generation
            inFlightContext = context
            mutableState.value = ExplanationUiState.Thinking(context.line)
            inFlight =
                scope.launch {
                    // The old request is gone from the bridge before this one is sent.
                    previous?.join()
                    val result = explainer.explain(context)
                    publish(mine, result.toUiState(context.line))
                }
        }
    }

    /** Cancels any request in flight and clears the panel. */
    fun dismiss() {
        synchronized(lock) {
            generation++
            inFlight?.cancel()
            inFlight = null
            inFlightContext = null
            mutableState.value = ExplanationUiState.Idle
        }
    }

    private fun publish(
        mine: Long,
        state: ExplanationUiState,
    ) {
        synchronized(lock) {
            if (mine != generation) return
            inFlightContext = null
            mutableState.value = state
        }
    }

    private fun ExplanationResult.toUiState(line: String): ExplanationUiState =
        when (this) {
            is ExplanationResult.Explained -> ExplanationUiState.Shown(line, explanation, fromCache)
            ExplanationResult.Offline -> ExplanationUiState.Unavailable(line, retryable = true, reason = OFFLINE_REASON)
            is ExplanationResult.Unavailable -> ExplanationUiState.Unavailable(line, retryable = false, reason = reason)
        }

    companion object {
        const val OFFLINE_REASON = "bridge timed out or disconnected"
    }
}
