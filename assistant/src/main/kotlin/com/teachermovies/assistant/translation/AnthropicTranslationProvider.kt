package com.teachermovies.assistant.translation

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.LogLevel
import com.anthropic.core.http.Interceptor
import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicRetryableException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.ThinkingConfigDisabled
import com.teachermovies.assistant.speech.SpeechLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

/**
 * English-to-Spanish subtitle-line translation through Claude Sonnet 5 ([MODEL]) with the official
 * Anthropic Java SDK. Every request sends exactly one subtitle line as the only user message, under
 * the fixed [SYSTEM_PROMPT], with extended thinking disabled and `max_tokens` = [MAX_TOKENS]; the
 * trimmed reply text is `Translated(text, fromCache = false)`. Production wiring puts
 * [CachingTranslationProvider] in front of it so a line is sent at most once per session.
 *
 * The subtitle text leaves the device: it is sent to Anthropic's API.
 *
 * The API key is read from [config] on every request (the user can change it in Configuración at
 * any time). A missing or blank key is `Unavailable("no translation provider configured")` without
 * opening any connection. The key only ever travels in the SDK's `x-api-key` header: the SDK's own
 * logging is switched off and no [TranslationResult] this class returns contains exception or
 * response-body text, so the key cannot leak through a reason string.
 *
 * Failures never throw (cancellation aside); they map through [TranslationError]:
 * connection refused / unknown host / no route / timeouts / other I/O -> `Offline`; 401/403 ->
 * `Unavailable("authentication rejected")`; 429 -> `Unavailable("rate limited")`; other non-2xx ->
 * `Unavailable("api error <code>")`; an unreadable body -> `Unavailable("malformed response")`; a
 * blank reply -> `Unavailable("empty translation")`. Only EN -> ES is supported; the reverse pair is
 * `Unavailable("unsupported language pair")`.
 */
class AnthropicTranslationProvider internal constructor(
    private val config: AnthropicApiConfigSource,
    private val clientFactory: (apiKey: String) -> AnthropicClient,
) : TranslationProvider {
    /** The production provider: a real OkHttp-backed SDK client per configured key. */
    constructor(config: AnthropicApiConfigSource) : this(config, { key -> buildClient(key) })

    override val id: String = ID

    private val clientLock = Mutex()
    private var client: CachedClient? = null

    override suspend fun translate(
        text: String,
        from: SpeechLanguage,
        to: SpeechLanguage,
    ): TranslationResult {
        TranslationRequests.shortcut(text, from, to)?.let { return it }
        if (from != SpeechLanguage.EN || to != SpeechLanguage.ES) {
            return TranslationError.UnsupportedLanguagePair.toResult()
        }
        val key = config.apiKey()?.trim()
        if (key.isNullOrEmpty()) return TranslationError.NotConfigured.toResult()
        return try {
            val sdk = clientFor(key)
            // The SDK call blocks; run it off the caller's thread and interrupt it on cancellation.
            val message = runInterruptible(Dispatchers.IO) { sdk.messages().create(request(text)) }
            val translation = replyText(message)
            if (translation.isEmpty()) {
                TranslationError.EmptyTranslation.toResult()
            } else {
                TranslationResult.Translated(translation, fromCache = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            classify(e).toResult()
        }
    }

    // One SDK client per key, rebuilt only when the user changes the key.
    private suspend fun clientFor(key: String): AnthropicClient =
        clientLock.withLock {
            client?.takeIf { it.apiKey == key }?.let { return@withLock it.client }
            client?.client?.close()
            clientFactory(key).also { client = CachedClient(key, it) }
        }

    private class CachedClient(
        val apiKey: String,
        val client: AnthropicClient,
    )

    internal companion object {
        const val ID = "anthropic"
        const val MODEL = "claude-sonnet-5"
        const val MAX_TOKENS = 256L
        const val SYSTEM_PROMPT =
            "Translate this English subtitle line to natural Spanish. Reply with the translation only."

        /** Per-attempt HTTP timeout: a single short line either comes back quickly or not at all. */
        val TIMEOUT: Duration = Duration.ofSeconds(15)

        /** One retry on 408/409/429/5xx/connection errors (SDK policy), then the failure is mapped. */
        const val MAX_RETRIES = 1

        /**
         * The SDK client the production constructor uses. [transport] replaces the HTTP layer in
         * tests (an [Interceptor] that never calls the real OkHttp client), so everything above
         * the socket -- request building, retries, status-to-exception mapping, JSON parsing -- is
         * the real SDK code path.
         */
        fun buildClient(
            apiKey: String,
            maxRetries: Int = MAX_RETRIES,
            transport: Interceptor? = null,
        ): AnthropicClient =
            AnthropicOkHttpClient
                .builder()
                .apiKey(apiKey)
                .timeout(TIMEOUT)
                .maxRetries(maxRetries)
                .logLevel(LogLevel.OFF)
                .apply { if (transport != null) addInterceptor(transport) }
                .build()

        fun request(text: String): MessageCreateParams =
            MessageCreateParams
                .builder()
                .model(MODEL)
                .maxTokens(MAX_TOKENS)
                .thinking(ThinkingConfigDisabled.builder().build())
                .system(SYSTEM_PROMPT)
                .addUserMessage(text)
                .build()

        fun replyText(message: Message): String =
            message
                .content()
                .mapNotNull { block -> block.text().orElse(null)?.text() }
                .joinToString("")
                .trim()

        /** Maps a failure to a [TranslationError]; never looks at (or keeps) any message text. */
        fun classify(e: Throwable): TranslationError =
            when (e) {
                is UnauthorizedException, is PermissionDeniedException -> TranslationError.AuthenticationRejected
                is RateLimitException -> TranslationError.RateLimited
                is AnthropicServiceException -> TranslationError.ApiError(e.statusCode())
                is AnthropicInvalidDataException -> TranslationError.MalformedResponse
                is AnthropicIoException, is AnthropicRetryableException, is IOException -> TranslationError.Offline
                else ->
                    if (e.causes().any { it is IOException }) {
                        TranslationError.Offline
                    } else {
                        TranslationError.Unexpected(e.javaClass.simpleName)
                    }
            }

        private fun Throwable.causes(): Sequence<Throwable> =
            generateSequence(cause) { it.cause?.takeIf { next -> next !== it } }.take(10)
    }
}
