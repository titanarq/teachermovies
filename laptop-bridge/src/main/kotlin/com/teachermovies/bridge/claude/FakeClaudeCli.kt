package com.teachermovies.bridge.claude

/**
 * A scripted [ClaudeCli] (#276) for the handler tests of #286/#291, in the main source set like
 * every fake of this project (ADR-0003): [ask] records the prompt and returns the next queued
 * outcome, or [fallback] once the queue is empty. No process, no network.
 */
class FakeClaudeCli(
    override val kind: String,
    outcomes: List<ClaudeOutcome> = emptyList(),
    var fallback: ClaudeOutcome = ClaudeOutcome.Answered(text = "ok", costUsd = 0.0, elapsedMs = 0),
) : ClaudeCli {
    private val queue = ArrayDeque(outcomes)
    private val asked = mutableListOf<String>()

    /** Every prompt [ask] received, in order. */
    val prompts: List<String>
        @Synchronized get() = asked.toList()

    var warmUps: Int = 0
        private set

    var closed: Boolean = false
        private set

    @Synchronized
    fun enqueue(outcome: ClaudeOutcome) {
        queue.addLast(outcome)
    }

    override suspend fun warmUp(): ClaudeOutcome? {
        warmUps += 1
        return null
    }

    override suspend fun ask(prompt: String): ClaudeOutcome =
        synchronized(this) {
            asked += prompt
            queue.removeFirstOrNull() ?: fallback
        }

    override suspend fun close() {
        closed = true
    }
}
