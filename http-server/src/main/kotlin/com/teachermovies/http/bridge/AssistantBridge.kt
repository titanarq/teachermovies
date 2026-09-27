package com.teachermovies.http.bridge

import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * The TV's one door to the laptop bridge (#275, ADR-0005 §2): typed jobs go out through
 * `GET /api/bridge/jobs` and their answers come back through `POST /api/bridge/jobs/{id}/result`.
 * The TV never runs an AI call itself. [BridgeJobHub] is the real implementation and
 * [FakeAssistantBridge] the one other modules' tests use (ADR-0003).
 */
interface AssistantBridge {
    /** Whether a bridge currently holds the job stream open. */
    val connected: StateFlow<Boolean>

    /**
     * Hands [job] to the connected bridge and suspends until its outcome, at most [timeout]. Never
     * throws for a bridge-side failure: every one is a [BridgeOutcome]. Cancelling the caller
     * cancels the job on the bridge too.
     */
    suspend fun submit(
        job: BridgeJob,
        timeout: Duration,
    ): BridgeOutcome
}

/**
 * A job as the TV's callers describe it, before the hub gives it an id. [slot] is the kind: at most
 * one job per kind is in flight, and a newer one replaces the older (which resolves as
 * [BridgeOutcome.Replaced]) -- the learner only ever waits for the line they asked about last.
 */
sealed interface BridgeJob {
    val slot: String

    /** Translate one captured English [line] into Castilian Spanish (ADR-0005 §6). */
    data class Translate(
        val line: String,
    ) : BridgeJob {
        override val slot: String get() = "translate"
    }

    /**
     * Explain the captured English [line] (ADR-0005 §7) with its context: the movie [title], the
     * English lines [before] and [after] it, and the aligned Spanish line when there is one.
     */
    data class Explain(
        val title: String?,
        val line: String,
        val before: List<String>,
        val after: List<String>,
        val spanishLine: String?,
    ) : BridgeJob {
        override val slot: String get() = "explain"
    }
}

/** How a [AssistantBridge.submit] ended. */
sealed interface BridgeOutcome {
    /** The bridge answered: [text] is the translation or the explain handler's document (#291). */
    data class Done(
        val text: String,
    ) : BridgeOutcome

    /** No bridge was connected when the job was submitted; answered at once, nothing was sent. */
    data object NoBridge : BridgeOutcome

    /** The bridge did not answer within the timeout; a cancel was sent to it. */
    data object TimedOut : BridgeOutcome

    /** The bridge answered with a failure: its [code] and optional [message]. */
    data class BridgeError(
        val code: String,
        val message: String?,
    ) : BridgeOutcome

    /** The stream the job went out on closed (dropped, or replaced by a newer bridge stream) before an answer. */
    data object Disconnected : BridgeOutcome

    /** A newer job of the same [BridgeJob.slot] was submitted; a cancel was sent to the bridge. */
    data object Replaced : BridgeOutcome

    /**
     * The job breaks the per-job limits (a line over `BridgeJobProtocol.MAX_LINE_CHARS` chars, or
     * the encoded job over `BridgeJobProtocol.MAX_PAYLOAD_BYTES`): never sent; [reason] says which.
     */
    data class Rejected(
        val reason: String,
    ) : BridgeOutcome
}
