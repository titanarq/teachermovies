package com.teachermovies.assistant.translation

import com.teachermovies.assistant.speech.SpeechLanguage
import java.time.Duration

/**
 * What the laptop bridge's job hub answered for one translation job (ADR-0005 §2; the outcomes of
 * #275's `submit(job, timeout)`). The members keep the hub's own names so the adapter that wiring
 * installs is a `when` with no logic in it.
 *
 * This is `:assistant`'s side of the seam: the hub lives in `:http-server` and `:bridge-protocol`,
 * which a feature module must not depend on (AGENTS.md), so [BridgeTranslationProvider] codes
 * against this type and whoever wires the assistant supplies the implementation.
 */
sealed interface BridgeTranslateOutcome {
    /** The bridge answered: [translation] is the Spanish text Claude produced on the laptop. */
    data class Done(
        val translation: String,
    ) : BridgeTranslateOutcome

    /** No bridge is connected, so the TV never queued the job and answers at once (ADR-0005 §2). */
    data object NoBridge : BridgeTranslateOutcome

    /** The bridge took the job and did not answer within the timeout. */
    data object TimedOut : BridgeTranslateOutcome

    /** The bridge's job stream went away while this job was in flight. */
    data object Disconnected : BridgeTranslateOutcome

    /** The bridge answered with a failure of its own: Claude refused, the daily cap is reached. */
    data class BridgeError(
        val reason: String,
    ) : BridgeTranslateOutcome
}

/**
 * The one thing [BridgeTranslationProvider] needs from the job hub: queue a translation job for
 * [text] in the [from] -> [to] direction and wait at most [timeout] for its outcome. Implementations
 * never throw (cancellation aside); a hub that cannot even queue the job says so with
 * [BridgeTranslateOutcome.NoBridge] or [BridgeTranslateOutcome.BridgeError].
 */
fun interface BridgeTranslateGateway {
    suspend fun translate(
        text: String,
        from: SpeechLanguage,
        to: SpeechLanguage,
        timeout: Duration,
    ): BridgeTranslateOutcome
}
