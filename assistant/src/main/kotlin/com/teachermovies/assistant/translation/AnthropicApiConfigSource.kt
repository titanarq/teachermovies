package com.teachermovies.assistant.translation

/**
 * Where [AnthropicTranslationProvider] reads the user's Anthropic API key from, at request time.
 * The key is typed by the user in the Configuración screen and kept in DataStore (`:core-model`);
 * the app wiring adapts that setting to this interface. It is the only place the provider ever
 * gets a key from (never an environment variable, a file or a build constant).
 *
 * Implementations must never log the key.
 */
fun interface AnthropicApiConfigSource {
    /** The current API key, or `null`/blank when the user has not configured one. */
    suspend fun apiKey(): String?
}
