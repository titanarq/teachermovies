package com.teachermovies.assistant.explanation.fake

import com.teachermovies.assistant.explanation.BridgeExplainGateway
import com.teachermovies.assistant.explanation.BridgeExplainOutcome
import com.teachermovies.assistant.explanation.ExplanationContext
import kotlinx.coroutines.delay
import java.time.Duration

/**
 * In-memory [BridgeExplainGateway] for tests (ADR-0003: fakes live in the main source set), and the
 * stand-in for #275's job hub until it is wired: canned documents from [documents], keyed by the
 * exact captured line, or once from [nextOutcome] when it is set; an unknown line is a
 * [BridgeExplainOutcome.BridgeError]. [delayMs] (virtual time under `runTest`) holds every answer back
 * so a test can overlap requests; [requests] records every context that reached the "bridge" with
 * its timeout, and [maxInFlight] the most requests that were ever inside [explain] at once.
 */
class FakeBridgeExplainGateway : BridgeExplainGateway {
    /** Canned answers: captured line -> the document the "bridge" returns. */
    val documents: MutableMap<String, String> = mutableMapOf()

    /** When set, the next request returns this instead of consulting [documents], and clears it. */
    var nextOutcome: BridgeExplainOutcome? = null

    var delayMs: Long = 0

    private val lock = Any()
    private val recorded = mutableListOf<Request>()
    private var current = 0
    private var peak = 0

    /** Every request that reached this hub, in order. */
    val requests: List<Request>
        get() = synchronized(lock) { recorded.toList() }

    val maxInFlight: Int
        get() = synchronized(lock) { peak }

    override suspend fun explain(
        context: ExplanationContext,
        timeout: Duration,
    ): BridgeExplainOutcome {
        synchronized(lock) {
            recorded += Request(context, timeout)
            current++
            peak = maxOf(peak, current)
        }
        try {
            if (delayMs > 0) delay(delayMs)
            nextOutcome?.let {
                nextOutcome = null
                return it
            }
            return documents[context.line]?.let { BridgeExplainOutcome.Done(it) }
                ?: BridgeExplainOutcome.BridgeError(NO_CANNED_EXPLANATION)
        } finally {
            synchronized(lock) { current-- }
        }
    }

    /** One job as the hub saw it. */
    data class Request(
        val context: ExplanationContext,
        val timeout: Duration,
    )

    companion object {
        const val NO_CANNED_EXPLANATION = "no canned explanation"
    }
}
