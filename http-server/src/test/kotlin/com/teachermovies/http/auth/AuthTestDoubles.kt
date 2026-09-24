package com.teachermovies.http.auth

import com.teachermovies.core.settings.AppSettings
import com.teachermovies.core.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.security.SecureRandom

/** Mirrors `DataStoreSettingsRepository` in memory: same defaults, same port validation. */
class InMemorySettingsRepository(
    initial: AppSettings = AppSettings(),
) : SettingsRepository {
    private val state = MutableStateFlow(initial)

    val current: AppSettings get() = state.value

    override val settings: Flow<AppSettings> = state

    override suspend fun setHttpPort(port: Int) {
        require(port in 1024..65535)
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
}

/** A deterministic [SecureRandom]: every byte it produces comes from a counter. */
class CountingSecureRandom : SecureRandom() {
    private var next = 1

    override fun nextBytes(bytes: ByteArray) {
        for (i in bytes.indices) bytes[i] = (next++ * 37).toByte()
    }
}

/** A clock tests move by hand. */
class FakeClock(
    var now: Long = 1_000_000L,
) : () -> Long {
    override fun invoke(): Long = now
}
