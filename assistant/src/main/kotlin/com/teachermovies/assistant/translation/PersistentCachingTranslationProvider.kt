package com.teachermovies.assistant.translation

import com.teachermovies.assistant.speech.SpeechLanguage
import com.teachermovies.core.repo.TranslationCacheRepository
import kotlin.coroutines.cancellation.CancellationException

/**
 * Room-backed cache in front of [delegate], over the `translation_cache` table #274 added
 * (`TranslationCacheRepository`; in production `RoomTranslationCacheRepository`, so answers survive a
 * restart and are bounded by `CachePruningPolicy` instead of by a fixed entry count).
 *
 * ADR-0005 §8: only [TranslationResult.Translated] is stored, never an error state. A line the bridge
 * could not answer -- [TranslationResult.Offline] or [TranslationResult.Unavailable] -- passes
 * through and stores nothing, so the next attempt asks again instead of remembering the line as
 * untranslatable.
 *
 * The table is keyed by the normalized English source text and its answer column is `translationEs`
 * (docs/modules/core-model.md), so only EN -> ES goes through it: any other pair reaches [delegate]
 * directly, is neither read nor written, and cannot shadow a row of the one direction the household
 * watches. Keys keep their case, because the repository normalizes whitespace only -- unlike
 * [CachingTranslationProvider], which folds case too.
 *
 * A hit returns `Translated(cached, fromCache = true)` without calling [delegate]; a miss returns the
 * delegate's own result with `fromCache = false`. [clock] supplies the `now` both repository methods
 * ask for, the way `AppContainer` gives its other repositories one.
 *
 * The cache is an optimisation, so a repository that fails (Room I/O on a TV) cannot break
 * [TranslationProvider]'s "never throws" contract: a read that throws is a miss and a write that
 * throws costs one more bridge job later, and either way the viewer still gets the translation. An
 * exception escaping [delegate] becomes [TranslationResult.Unavailable], as in
 * [CachingTranslationProvider]. Cancellation is rethrown, never caught.
 */
class PersistentCachingTranslationProvider(
    private val delegate: TranslationProvider,
    private val cache: TranslationCacheRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : TranslationProvider {
    override val id: String = delegate.id

    override suspend fun translate(
        text: String,
        from: SpeechLanguage,
        to: SpeechLanguage,
    ): TranslationResult {
        TranslationRequests.shortcut(text, from, to)?.let { return it }
        // The table holds one direction only; any other pair is the delegate's own business.
        val cacheable = from == SpeechLanguage.EN && to == SpeechLanguage.ES
        if (cacheable) {
            readCache(text)?.let { return TranslationResult.Translated(it, fromCache = true) }
        }
        return when (val result = askDelegate(text, from, to)) {
            is TranslationResult.Translated -> {
                if (cacheable) writeCache(text, result.text)
                result.copy(fromCache = false)
            }

            TranslationResult.Offline, is TranslationResult.Unavailable -> {
                result
            }
        }
    }

    private suspend fun askDelegate(
        text: String,
        from: SpeechLanguage,
        to: SpeechLanguage,
    ): TranslationResult =
        try {
            delegate.translate(text, from, to)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TranslationResult.Unavailable("translation provider '${delegate.id}' failed: ${e.javaClass.simpleName}")
        }

    private suspend fun readCache(text: String): String? =
        try {
            cache.translationOf(text, clock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An unreadable cache is a miss: the line is asked of the bridge again, which is what a
            // cold cache does anyway.
            null
        }

    private suspend fun writeCache(
        text: String,
        translationEs: String,
    ) {
        try {
            cache.store(text, translationEs, clock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Losing the row costs one more bridge job for this line; it is not worth discarding the
            // answer the bridge already produced.
        }
    }
}
