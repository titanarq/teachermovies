package com.teachermovies.assistant.explanation

import java.time.Duration

/**
 * What the laptop bridge's job hub answered for one explain job (ADR-0005 §2/§7; #275's
 * `BridgeOutcome`). The same split as `BridgeTranslateOutcome`, kept separate because the answer is a
 * document, not a line of text.
 *
 * This is `:assistant`'s side of the seam: the hub lives in `:http-server`, which a feature module
 * must not depend on (AGENTS.md), so [LineExplainer] codes against this type and whoever wires the
 * assistant adapts `AssistantBridge.submit(BridgeJob.Explain(...), timeout)` to it -- `Replaced` and
 * `Rejected` included, as a [BridgeError] each.
 */
sealed interface BridgeExplainOutcome {
    /** The bridge answered: [document] is the explain handler's JSON (#291), verbatim. */
    data class Done(
        val document: String,
    ) : BridgeExplainOutcome

    /** No bridge is connected, so the TV never queued the job and answers at once (ADR-0005 §2). */
    data object NoBridge : BridgeExplainOutcome

    /** The bridge took the job and did not answer within the timeout. */
    data object TimedOut : BridgeExplainOutcome

    /** The bridge's job stream went away while this job was in flight. */
    data object Disconnected : BridgeExplainOutcome

    /** The bridge (or the hub) refused or failed the job: Claude refused, the daily cap is reached. */
    data class BridgeError(
        val reason: String,
    ) : BridgeExplainOutcome
}

/**
 * The one thing [LineExplainer] needs from the job hub: queue an explain job for [context] and wait
 * at most [timeout] for its outcome. Implementations never throw (cancellation aside), and cancelling
 * the caller cancels the job on the bridge (the hub's own contract).
 */
fun interface BridgeExplainGateway {
    suspend fun explain(
        context: ExplanationContext,
        timeout: Duration,
    ): BridgeExplainOutcome
}
