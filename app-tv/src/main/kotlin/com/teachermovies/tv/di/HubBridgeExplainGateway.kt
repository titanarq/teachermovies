package com.teachermovies.tv.di

import com.teachermovies.assistant.explanation.BridgeExplainGateway
import com.teachermovies.assistant.explanation.BridgeExplainOutcome
import com.teachermovies.assistant.explanation.ExplanationContext
import com.teachermovies.http.bridge.AssistantBridge
import com.teachermovies.http.bridge.BridgeJob
import com.teachermovies.http.bridge.BridgeOutcome
import java.time.Duration
import kotlin.time.toKotlinDuration

/**
 * The adapter `BridgeExplainGateway` asks wiring for (#292, #293): an explain job on the job hub
 * [bridge] (#275) carrying [ExplanationContext] field for field, its outcome renamed member for
 * member, as [HubBridgeTranslateGateway] does for LEFT.
 *
 * [BridgeOutcome.Replaced] -- a newer explanation took the slot -- only reaches a caller that has
 * already moved on to the newer line, so it is [BridgeExplainOutcome.Disconnected]. A bridge failure
 * keeps its code, and a job the hub refused to send its reason, as the reason, which never reaches
 * the screen.
 */
class HubBridgeExplainGateway(
    private val bridge: AssistantBridge,
) : BridgeExplainGateway {
    override suspend fun explain(
        context: ExplanationContext,
        timeout: Duration,
    ): BridgeExplainOutcome =
        when (val outcome = bridge.submit(context.toJob(), timeout.toKotlinDuration())) {
            is BridgeOutcome.Done -> BridgeExplainOutcome.Done(outcome.text)
            BridgeOutcome.NoBridge -> BridgeExplainOutcome.NoBridge
            BridgeOutcome.TimedOut -> BridgeExplainOutcome.TimedOut
            BridgeOutcome.Disconnected, BridgeOutcome.Replaced -> BridgeExplainOutcome.Disconnected
            is BridgeOutcome.BridgeError -> BridgeExplainOutcome.BridgeError(outcome.code)
            is BridgeOutcome.Rejected -> BridgeExplainOutcome.BridgeError(outcome.reason)
        }

    private fun ExplanationContext.toJob(): BridgeJob.Explain =
        BridgeJob.Explain(
            title = title,
            line = line,
            before = before,
            after = after,
            spanishLine = spanishLine,
        )
}
