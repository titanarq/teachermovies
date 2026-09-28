package com.teachermovies.bridge.claude

import com.teachermovies.bridge.protocol.BridgeJobResultDto
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Claude, as one job kind's handler sees it (#276): one prompt in, one [ClaudeOutcome] out. The
 * translate and explain handlers of #286/#291 each hold one, built with their own system prompt;
 * [ClaudeCliTransport] is the real one and [FakeClaudeCli] the one their tests use (ADR-0003).
 *
 * [ask] never throws except for cancellation: every foreseeable failure is an outcome.
 */
interface ClaudeCli {
    /** The job kind this conversation serves: one process -- and one system prompt -- per kind. */
    val kind: String

    /** Starts the process and runs its warm-up turn now, so the first job does not wait for either. */
    suspend fun warmUp(): ClaudeOutcome?

    suspend fun ask(prompt: String): ClaudeOutcome

    /** Ends every process this conversation holds. */
    suspend fun close()
}

/** What one [ClaudeCli.ask] came to. */
sealed interface ClaudeOutcome {
    /** Claude's reply text, verbatim -- extracting and validating any JSON in it is the handler's job. */
    data class Answered(
        val text: String,
        val costUsd: Double?,
        val elapsedMs: Long,
    ) : ClaudeOutcome

    /** The subscription's usage window is spent for now (a `429`). */
    data object RateLimited : ClaudeOutcome

    /** No reply within the turn timeout; that process was killed and replaced. */
    data object TimedOut : ClaudeOutcome

    /** [DailyCap] reached: no job reaches Claude again until tomorrow. */
    data class DailyCapReached(
        val limit: Int,
    ) : ClaudeOutcome

    /**
     * The CLI reported it would bill [apiKeySource] instead of the human's own login (`none`), or did
     * not say: this conversation will not ask Claude again until the bridge restarts.
     */
    data class Refused(
        val apiKeySource: String?,
    ) : ClaudeOutcome

    /** The CLI could not be started, died, or failed the turn; [reason] is a short Spanish diagnostic. */
    data class Failed(
        val reason: String,
    ) : ClaudeOutcome

    /**
     * The job result a handler posts for an outcome that is not an answer, null for [Answered]: the
     * codes the TV maps onto "no disponible" (ADR-0005, Consequences).
     */
    fun failureResult(): BridgeJobResultDto.Failed? =
        when (this) {
            is Answered -> null
            RateLimited -> BridgeJobResultDto.Failed(RATE_LIMITED, "Claude: límite de uso alcanzado (429)")
            TimedOut -> BridgeJobResultDto.Failed(TIMEOUT, "Claude no ha respondido a tiempo")
            is DailyCapReached -> BridgeJobResultDto.Failed(DAILY_CAP, "tope diario de $limit trabajos alcanzado")
            is Refused -> BridgeJobResultDto.Failed(API_KEY_BILLING, "Claude Code no usa la sesión del portátil")
            is Failed -> BridgeJobResultDto.Failed(CLAUDE_ERROR, reason)
        }

    companion object {
        const val RATE_LIMITED = "rate_limited"
        const val TIMEOUT = "timeout"
        const val DAILY_CAP = "daily_cap"
        const val API_KEY_BILLING = "api_key_billing"
        const val CLAUDE_ERROR = "claude_error"
    }
}

/**
 * The daily job cap of ADR-0005 §3 (#276): at most [limit] jobs a day reach Claude, counted per
 * calendar day in [zone]. One instance is shared by every [ClaudeCliTransport] of a bridge, so the
 * cap covers all kinds together. Warm-up turns are not jobs and do not count.
 *
 * The count lives in memory: a restarted bridge starts the day over (see the module doc).
 */
class DailyCap(
    val limit: Int,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private var day: LocalDate? = null
    private var used = 0

    init {
        require(limit >= 1) { "the daily cap must allow at least one job" }
    }

    /** Takes one job's place for today, or returns false when today's [limit] is used up. */
    @Synchronized
    fun tryAcquire(): Boolean {
        rollOver()
        if (used >= limit) return false
        used += 1
        return true
    }

    @Synchronized
    fun isReached(): Boolean {
        rollOver()
        return used >= limit
    }

    @Synchronized
    fun usedToday(): Int {
        rollOver()
        return used
    }

    private fun rollOver() {
        val today = clock().atZone(zone).toLocalDate()
        if (today != day) {
            day = today
            used = 0
        }
    }
}
