package com.teachermovies.assistant.explanation

import com.teachermovies.core.repo.ExplanationCacheRepository
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

/** How [LineExplainer.explain] ended. Never an exception (cancellation aside). */
sealed interface ExplanationResult {
    /** An explanation to show; [fromCache] when it came from `explanation_cache`, not the bridge. */
    data class Explained(
        val explanation: Explanation,
        val fromCache: Boolean,
    ) : ExplanationResult

    /** The laptop may answer next time: the job timed out or its stream went away. */
    data object Offline : ExplanationResult

    /** Asking again now would answer the same: no bridge, a bridge error, or an unusable reply. */
    data class Unavailable(
        val reason: String,
    ) : ExplanationResult
}

/**
 * Explains one captured line through the laptop bridge (ADR-0005 §7), with the Room cache #274 added
 * in front of it (§8: only successes are stored).
 *
 * The cache row is keyed by the movie title, the line and the bridge prompt version, so a changed
 * prompt never serves an explanation an older prompt wrote. The version used for the lookup is the one
 * the bridge last tagged a reply with (#291), [initialPromptVersion] until any reply has; a reply
 * that carries a tag is stored under that tag, one without it under the version in use. After a
 * restart the first lookup uses [initialPromptVersion] again, so bumping it together with the
 * bridge's prompt is what retires old rows at once; until then a changed prompt retires them from
 * its first reply on. A context with no title is never cached: the table needs one, and one line
 * can mean different things in different films.
 *
 * It asks the bridge only when [explain] is called -- nothing here looks ahead of the learner
 * (ADR-0005 §7, no prefetch) -- and it never speaks: the result is text for the panel (no TTS).
 *
 * The cache is an optimisation, so a repository that throws is a miss on read and ignored on write,
 * as in `PersistentCachingTranslationProvider`. A gateway that throws is [ExplanationResult.Unavailable].
 */
class LineExplainer(
    private val gateway: BridgeExplainGateway,
    private val cache: ExplanationCacheRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val timeout: Duration = TIMEOUT,
    initialPromptVersion: String = PROMPT_VERSION,
) {
    @Volatile
    private var promptVersionInUse: String = initialPromptVersion

    /** The prompt version the next cache lookup uses. */
    val promptVersion: String
        get() = promptVersionInUse

    suspend fun explain(context: ExplanationContext): ExplanationResult {
        if (context.line.isBlank()) return ExplanationResult.Unavailable(BLANK_LINE_REASON)
        val title = context.title.orEmpty()
        val version = promptVersionInUse
        val cached = readCache(title, context.line, version)
        if (cached != null) return ExplanationResult.Explained(cached, fromCache = true)
        return when (val outcome = askGateway(context)) {
            is BridgeExplainOutcome.Done -> done(title, context.line, version, outcome.document)
            BridgeExplainOutcome.NoBridge -> ExplanationResult.Unavailable(NO_BRIDGE_REASON)
            BridgeExplainOutcome.TimedOut, BridgeExplainOutcome.Disconnected -> ExplanationResult.Offline
            is BridgeExplainOutcome.BridgeError -> ExplanationResult.Unavailable(bridgeError(outcome.reason))
        }
    }

    private suspend fun done(
        title: String,
        line: String,
        versionAsked: String,
        document: String,
    ): ExplanationResult {
        val explanation =
            Explanation.parse(document) ?: return ExplanationResult.Unavailable(INVALID_REPLY_REASON)
        val version = explanation.promptVersion ?: versionAsked
        promptVersionInUse = version
        writeCache(title, line, version, document)
        return ExplanationResult.Explained(explanation, fromCache = false)
    }

    private fun bridgeError(reason: String): String = "$BRIDGE_ERROR_REASON: $reason"

    /** A hub that breaks its own contract still may not make [explain] throw. */
    private suspend fun askGateway(context: ExplanationContext): BridgeExplainOutcome =
        try {
            gateway.explain(context, timeout)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BridgeExplainOutcome.BridgeError(e.javaClass.simpleName)
        }

    private suspend fun readCache(
        title: String,
        line: String,
        version: String,
    ): Explanation? =
        try {
            // A row that no longer parses is a miss: the bridge is asked again and overwrites it.
            cache.explanationFor(title, line, version, clock())?.let(Explanation::parse)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private suspend fun writeCache(
        title: String,
        line: String,
        version: String,
        document: String,
    ) {
        try {
            // A blank title stores nothing (ExplanationCacheRepository's own rule).
            cache.store(title, line, version, document, clock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Losing the row costs one more bridge job for this line, not the answer on screen.
        }
    }

    companion object {
        /**
         * The explain prompt version the TV assumes until the bridge tags a reply (#291). Bump it
         * together with the bridge's prompt.
         */
        const val PROMPT_VERSION = "explain-v2"

        const val BLANK_LINE_REASON = "blank line"

        /** No bridge holds the job stream open (ADR-0005 §2), so the TV never queued the job. */
        const val NO_BRIDGE_REASON = "no bridge connected"

        /** The bridge reported a failure of its own, or its call threw. */
        const val BRIDGE_ERROR_REASON = "bridge error"

        /** The bridge answered with something that is not an explanation document. */
        const val INVALID_REPLY_REASON = "invalid explanation"

        /** A short structured reply through Sonnet at low effort (ADR-0005 §3), plus the two HTTP hops. */
        val TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
