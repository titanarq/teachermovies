package com.teachermovies.assistant.translation.fake

import com.teachermovies.assistant.speech.SpeechLanguage
import com.teachermovies.assistant.translation.BridgeTranslateGateway
import com.teachermovies.assistant.translation.BridgeTranslateOutcome
import java.time.Duration

/**
 * In-memory [BridgeTranslateGateway] for tests (ADR-0003: fakes live in the main source set), and the
 * stand-in for #275's job hub until it is wired: canned answers from [translations], keyed by the
 * exact text, or once from [nextOutcome] when it is set. Every call is recorded in [requests] with the
 * direction and the timeout it was given, so a test can assert what the provider actually asked for.
 *
 * Unlike [FakeTranslationProvider] this one applies no shortcut rule: the provider under test owns
 * that, and whatever reaches a real hub is a job.
 */
class FakeBridgeTranslateGateway : BridgeTranslateGateway {
    /** Canned answers: source text -> the Spanish the "bridge" returns. */
    val translations: MutableMap<String, String> = mutableMapOf()

    /** When set, the next request returns this instead of consulting [translations], and clears it. */
    var nextOutcome: BridgeTranslateOutcome? = null

    private val recorded = mutableListOf<Request>()

    /** Every request that reached this hub, in order. */
    val requests: List<Request>
        get() = synchronized(recorded) { recorded.toList() }

    override suspend fun translate(
        text: String,
        from: SpeechLanguage,
        to: SpeechLanguage,
        timeout: Duration,
    ): BridgeTranslateOutcome {
        synchronized(recorded) { recorded += Request(text, from, to, timeout) }
        nextOutcome?.let {
            nextOutcome = null
            return it
        }
        return translations[text]?.let { BridgeTranslateOutcome.Done(it) }
            ?: BridgeTranslateOutcome.BridgeError(NO_CANNED_TRANSLATION)
    }

    /** One job as the hub saw it. */
    data class Request(
        val text: String,
        val from: SpeechLanguage,
        val to: SpeechLanguage,
        val timeout: Duration,
    )

    companion object {
        const val NO_CANNED_TRANSLATION = "no canned translation"
    }
}
