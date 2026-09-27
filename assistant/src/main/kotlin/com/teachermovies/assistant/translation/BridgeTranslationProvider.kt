package com.teachermovies.assistant.translation

import com.teachermovies.assistant.speech.SpeechLanguage
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

/**
 * Translates one line by handing it to the laptop bridge, which runs Claude Code on the household's
 * own subscription (ADR-0005 §1/§3): the TV runs no AI call itself and stores no API key.
 *
 * Every hub outcome becomes a [TranslationResult], so `translate` still never throws and the
 * assistant panel degrades instead of failing (VISION §7). The split is the one the viewer feels:
 * [BridgeTranslateOutcome.NoBridge] -- no laptop is paired, or its bridge is not running -- is
 * [TranslationResult.Unavailable], because asking again right now would answer the same;
 * [BridgeTranslateOutcome.TimedOut] and [BridgeTranslateOutcome.Disconnected] are
 * [TranslationResult.Offline], the "the laptop may answer next time" case. A job the bridge itself
 * failed, and an answer that came back empty, are [TranslationResult.Unavailable] too.
 *
 * Reasons are the debug half of [TranslationError]'s rule: lower case, no exception message beyond its
 * class name, and none of it reaches the screen -- `AssistantSpeechController` maps any `Unavailable`
 * to `Failed(UNAVAILABLE)` and the panel shows "Traducción no disponible". The one free text is the
 * bridge's own [BridgeTranslateOutcome.BridgeError] reason, which travels verbatim: it comes from the
 * household's laptop (#286), not from a third-party response, so it can stay short and say nothing
 * secret.
 */
class BridgeTranslationProvider(
    private val gateway: BridgeTranslateGateway,
    private val timeout: Duration = TIMEOUT,
) : TranslationProvider {
    override val id: String = ID

    override suspend fun translate(
        text: String,
        from: SpeechLanguage,
        to: SpeechLanguage,
    ): TranslationResult {
        TranslationRequests.shortcut(text, from, to)?.let { return it }
        return askGateway(text, from, to).toResult()
    }

    /** A hub that breaks its own contract still may not make [translate] throw. */
    private suspend fun askGateway(
        text: String,
        from: SpeechLanguage,
        to: SpeechLanguage,
    ): BridgeTranslateOutcome =
        try {
            gateway.translate(text, from, to, timeout)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BridgeTranslateOutcome.BridgeError(e.javaClass.simpleName)
        }

    private fun BridgeTranslateOutcome.toResult(): TranslationResult =
        when (this) {
            is BridgeTranslateOutcome.Done -> doneResult(translation)
            BridgeTranslateOutcome.NoBridge -> TranslationResult.Unavailable(NO_BRIDGE_REASON)
            BridgeTranslateOutcome.TimedOut, BridgeTranslateOutcome.Disconnected -> TranslationResult.Offline
            is BridgeTranslateOutcome.BridgeError -> TranslationResult.Unavailable("$BRIDGE_ERROR_REASON: $reason")
        }

    /** What the bridge answered: a translation, unless all it sent was whitespace. */
    private fun doneResult(translation: String): TranslationResult {
        val text = translation.trim()
        return if (text.isEmpty()) {
            TranslationResult.Unavailable(EMPTY_REASON)
        } else {
            TranslationResult.Translated(text, fromCache = false)
        }
    }

    companion object {
        const val ID = "bridge"

        /** No bridge holds the job stream open (ADR-0005 §2), so the TV never queued the job. */
        const val NO_BRIDGE_REASON = "no bridge connected"

        /** The bridge answered with nothing but whitespace: not a translation. */
        const val EMPTY_REASON = "empty translation"

        /** The bridge reported a failure of its own, or its call threw. */
        const val BRIDGE_ERROR_REASON = "bridge error"

        /** One short line through Sonnet at low effort (ADR-0005 §3/§6), plus the two HTTP hops. */
        val TIMEOUT: Duration = Duration.ofSeconds(15)
    }
}
