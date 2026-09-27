package com.teachermovies.http.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration

/**
 * In-memory [AssistantBridge] for other modules' tests (ADR-0003): records every job and answers
 * with [respond] while [connected] is true, [BridgeOutcome.NoBridge] while it is false -- the same
 * immediate answer the real hub gives with no bridge connected.
 */
class FakeAssistantBridge(
    connected: Boolean = true,
    var respond: suspend (BridgeJob) -> BridgeOutcome = { BridgeOutcome.Done("") },
) : AssistantBridge {
    private val state = MutableStateFlow(connected)
    override val connected: StateFlow<Boolean> = state.asStateFlow()

    private val jobs = mutableListOf<BridgeJob>()

    /** Every job submitted so far, NoBridge ones included, oldest first. */
    val submitted: List<BridgeJob> get() = synchronized(jobs) { jobs.toList() }

    fun setConnected(value: Boolean) {
        state.value = value
    }

    override suspend fun submit(
        job: BridgeJob,
        timeout: Duration,
    ): BridgeOutcome {
        synchronized(jobs) { jobs += job }
        return if (state.value) respond(job) else BridgeOutcome.NoBridge
    }
}
