package com.teachermovies.assistant.translation.fake

import com.teachermovies.assistant.speech.SpeechLanguage
import com.teachermovies.assistant.translation.TranslationProvider
import com.teachermovies.assistant.translation.TranslationRequests
import com.teachermovies.assistant.translation.TranslationResult
import kotlinx.coroutines.delay

/**
 * In-memory [TranslationProvider] for tests (ADR-0003: fakes live in the main source set).
 * Answers from [translations] (keyed by the exact text passed to [translate], languages ignored),
 * or once from [nextResult] when it is set. Shortcut requests (blank text, `from == to`) are
 * answered by [TranslationRequests.shortcut] and are not recorded in [requests].
 */
class FakeTranslationProvider(
    override val id: String = "fake",
) : TranslationProvider {
    /** Canned translations: source text -> translated text. */
    val translations: MutableMap<String, String> = mutableMapOf()

    /** Virtual-time delay before each answer (under `runTest` it costs no real time). */
    var delayMs: Long = 0

    /** When set, the next real request returns this instead of consulting [translations]. */
    var nextResult: TranslationResult? = null

    private val recorded = mutableListOf<String>()

    /** Every text that really reached this provider, in order. */
    val requests: List<String>
        get() = synchronized(recorded) { recorded.toList() }

    override suspend fun translate(
        text: String,
        from: SpeechLanguage,
        to: SpeechLanguage,
    ): TranslationResult {
        TranslationRequests.shortcut(text, from, to)?.let { return it }
        synchronized(recorded) { recorded += text }
        if (delayMs > 0) delay(delayMs)
        nextResult?.let {
            nextResult = null
            return it
        }
        return translations[text]?.let { TranslationResult.Translated(it, fromCache = false) }
            ?: TranslationResult.Unavailable(NO_CANNED_TRANSLATION)
    }

    companion object {
        const val NO_CANNED_TRANSLATION = "no canned translation"
    }
}
