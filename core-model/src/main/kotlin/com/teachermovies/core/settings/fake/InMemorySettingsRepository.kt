package com.teachermovies.core.settings.fake

import com.teachermovies.core.settings.AppSettings
import com.teachermovies.core.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

private const val MIN_HTTP_PORT = 1024
private const val MAX_HTTP_PORT = 65535

/**
 * Deterministic [SettingsRepository] over a [MutableStateFlow], for other modules' JVM tests
 * (ADR-0003): no DataStore, no Android runtime, no real time. It applies the same rules as
 * `DataStoreSettingsRepository` -- the same defaults and the 1024..65535 check in [setHttpPort],
 * where a rejected port leaves the stored one untouched -- and the rule in
 * [setTranslationApiKey] that a null or blank key clears the stored one.
 */
class InMemorySettingsRepository(
    initial: AppSettings = AppSettings(),
) : SettingsRepository {
    private val state = MutableStateFlow(initial)

    override val settings: StateFlow<AppSettings> = state

    /** The settings as they are right now, for assertions. */
    val current: AppSettings get() = state.value

    override suspend fun setHttpPort(port: Int) {
        require(port in MIN_HTTP_PORT..MAX_HTTP_PORT) {
            "HTTP port must be in $MIN_HTTP_PORT..$MAX_HTTP_PORT, was $port"
        }
        state.update { it.copy(httpPort = port) }
    }

    override suspend fun setDownloadVolumeId(id: String?) {
        state.update { it.copy(downloadVolumeId = id) }
    }

    override suspend fun addAuthTokenHash(hash: String) {
        state.update { it.copy(authTokenHashes = it.authTokenHashes + hash) }
    }

    override suspend fun clearAuthTokenHashes() {
        state.update { it.copy(authTokenHashes = emptySet()) }
    }

    override suspend fun setFirstRunCompleted(done: Boolean) {
        state.update { it.copy(firstRunCompleted = done) }
    }

    override suspend fun setAutostartOnBoot(enabled: Boolean) {
        state.update { it.copy(autostartOnBoot = enabled) }
    }

    override suspend fun setTranslationApiKey(key: String?) {
        state.update { it.copy(translationApiKey = key?.takeUnless { k -> k.isBlank() }) }
    }
}
