package com.teachermovies.assistant.translation

/**
 * Why a network translation provider could not translate a line. Internal to the provider's error
 * handling: callers only ever see the [TranslationResult] that [toResult] maps it to. No member
 * carries free text from an exception or a response body, so nothing secret can travel through it.
 */
internal sealed interface TranslationError {
    /** No API key configured: the request never leaves the device. */
    data object NotConfigured : TranslationError

    /** Connection refused, unknown host, no route, a timeout or any other I/O failure. */
    data object Offline : TranslationError

    /** HTTP 401 or 403. */
    data object AuthenticationRejected : TranslationError

    /** HTTP 429. */
    data object RateLimited : TranslationError

    /** Any other non-2xx status. */
    data class ApiError(
        val statusCode: Int,
    ) : TranslationError

    /** A 2xx body that cannot be read as the expected message shape. */
    data object MalformedResponse : TranslationError

    /** The model answered, but with no text once trimmed. */
    data object EmptyTranslation : TranslationError

    /** The provider only translates between the language pair it was built for. */
    data object UnsupportedLanguagePair : TranslationError

    /** An exception nobody anticipated; only its class name is kept, never its message. */
    data class Unexpected(
        val type: String,
    ) : TranslationError

    fun toResult(): TranslationResult =
        when (this) {
            NotConfigured -> TranslationResult.Unavailable(NullTranslationProvider.REASON)
            Offline -> TranslationResult.Offline
            AuthenticationRejected -> TranslationResult.Unavailable("authentication rejected")
            RateLimited -> TranslationResult.Unavailable("rate limited")
            is ApiError -> TranslationResult.Unavailable("api error $statusCode")
            MalformedResponse -> TranslationResult.Unavailable("malformed response")
            EmptyTranslation -> TranslationResult.Unavailable("empty translation")
            UnsupportedLanguagePair -> TranslationResult.Unavailable("unsupported language pair")
            is Unexpected -> TranslationResult.Unavailable("unexpected error $type")
        }
}
