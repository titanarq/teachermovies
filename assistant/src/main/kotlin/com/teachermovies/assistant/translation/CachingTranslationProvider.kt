package com.teachermovies.assistant.translation

import com.teachermovies.assistant.speech.SpeechLanguage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * In-memory LRU cache in front of [delegate]. The key is the trimmed, case-folded,
 * whitespace-collapsed text plus both language tags, so `"Hello  there"` and `" hello there"` share
 * one entry. Only [TranslationResult.Translated] is cached: [TranslationResult.Offline] and
 * [TranslationResult.Unavailable] are returned as they are and a later call retries.
 *
 * A hit returns `Translated(cachedText, fromCache = true)` without calling [delegate]; a miss
 * returns the delegate's own result, with `fromCache = false` when it is a translation. Once the
 * cache holds more than [maxEntries] entries the least recently used one is evicted.
 *
 * Safe to call from several coroutines at once: a [Mutex] guards the cache and is held across the
 * delegate call, so concurrent requests are served one at a time and two concurrent calls for the
 * same line reach [delegate] only once (the second one is a hit). Subtitle lines are translated
 * one on demand at a time, so serialising them costs nothing noticeable.
 *
 * An exception escaping [delegate] (a contract violation) is turned into
 * [TranslationResult.Unavailable] so [translate] still never throws; cancellation is rethrown.
 */
class CachingTranslationProvider(
    private val delegate: TranslationProvider,
    private val maxEntries: Int = 200,
) : TranslationProvider {
    init {
        require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
    }

    override val id: String = delegate.id

    private val mutex = Mutex()

    // accessOrder = true: iteration order is least recently accessed first.
    private val cache =
        object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > maxEntries
        }

    override suspend fun translate(
        text: String,
        from: SpeechLanguage,
        to: SpeechLanguage,
    ): TranslationResult {
        TranslationRequests.shortcut(text, from, to)?.let { return it }
        val key = cacheKey(text, from, to)
        return mutex.withLock {
            cache[key]?.let { return@withLock TranslationResult.Translated(it, fromCache = true) }
            when (val result = askDelegate(text, from, to)) {
                is TranslationResult.Translated -> {
                    cache[key] = result.text
                    result.copy(fromCache = false)
                }

                TranslationResult.Offline, is TranslationResult.Unavailable -> {
                    result
                }
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

    internal companion object {
        private val WHITESPACE = Regex("\\s+")

        fun cacheKey(
            text: String,
            from: SpeechLanguage,
            to: SpeechLanguage,
        ): String {
            val normalised = text.trim().lowercase(Locale.ROOT).replace(WHITESPACE, " ")
            return "${from.tag}>${to.tag}|$normalised"
        }
    }
}
