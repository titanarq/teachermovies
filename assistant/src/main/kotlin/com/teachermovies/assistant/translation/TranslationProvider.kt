package com.teachermovies.assistant.translation

import com.teachermovies.assistant.speech.SpeechLanguage

/** Outcome of [TranslationProvider.translate]. Every failure is one of these; nothing is thrown. */
sealed interface TranslationResult {
    /** [text] is the translation; [fromCache] is true when no backend was actually asked. */
    data class Translated(
        val text: String,
        val fromCache: Boolean,
    ) : TranslationResult

    /**
     * Graceful degradation: a provider is configured, but the device cannot reach it right now.
     * A later attempt may succeed.
     */
    data object Offline : TranslationResult

    /** The line cannot be translated (no provider configured, the provider refused, ...). */
    data class Unavailable(
        val reason: String,
    ) : TranslationResult
}

/**
 * Translates one subtitle line (English to Spanish by default). Implementations are swappable
 * (ADR-0001 §6): [NullTranslationProvider] until one is configured, a network provider later,
 * [CachingTranslationProvider] in front of either.
 *
 * Contract: [translate] never throws (coroutine cancellation aside) and every failure is a
 * [TranslationResult]. Blank [text], or `from == to`, is answered with
 * `Translated(text.trim(), fromCache = true)` without asking any backend: implementations start
 * with [TranslationRequests.shortcut].
 */
interface TranslationProvider {
    /** Stable identifier of the backend, e.g. `"none"`. */
    val id: String

    suspend fun translate(
        text: String,
        from: SpeechLanguage = SpeechLanguage.EN,
        to: SpeechLanguage = SpeechLanguage.ES,
    ): TranslationResult
}

/** Helpers shared by every [TranslationProvider] implementation. */
object TranslationRequests {
    /**
     * The answer for a request that needs no backend: `Translated(text.trim(), fromCache = true)`
     * for blank [text] or when [from] equals [to]; `null` when the request must really be
     * translated.
     */
    fun shortcut(
        text: String,
        from: SpeechLanguage,
        to: SpeechLanguage,
    ): TranslationResult.Translated? =
        if (text.isBlank() || from == to) {
            TranslationResult.Translated(text.trim(), fromCache = true)
        } else {
            null
        }
}

/**
 * The provider the app uses until a real one is configured: every request that passes
 * [TranslationRequests.shortcut] is `Unavailable("no translation provider configured")`.
 */
object NullTranslationProvider : TranslationProvider {
    const val REASON = "no translation provider configured"

    override val id: String = "none"

    override suspend fun translate(
        text: String,
        from: SpeechLanguage,
        to: SpeechLanguage,
    ): TranslationResult = TranslationRequests.shortcut(text, from, to) ?: TranslationResult.Unavailable(REASON)
}
