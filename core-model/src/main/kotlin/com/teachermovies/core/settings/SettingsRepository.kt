package com.teachermovies.core.settings

import kotlinx.coroutines.flow.Flow

/**
 * The one path that reads and writes app settings; every module that needs a setting goes through
 * this instead of touching the store behind it.
 *
 * [settings] emits the current value on collection and then on every change, so a collector never
 * has to poll. Each setter suspends until the change is durable, and returns only after the new
 * value is what [settings] will emit next.
 */
interface SettingsRepository {
    val settings: Flow<AppSettings>

    /**
     * Sets the port the HTTP server binds.
     *
     * @throws IllegalArgumentException if [port] is outside 1024..65535: below 1024 is a privileged
     *   range an unprivileged Android app cannot bind, and above 65535 is not a port.
     */
    suspend fun setHttpPort(port: Int)

    /** Chooses the volume downloads go to, or clears the choice when [id] is null. */
    suspend fun setDownloadVolumeId(id: String?)

    /**
     * Records one more phone-scoped pairing token hash, keeping the ones already stored. A
     * bridge-scoped token goes to [addBridgeTokenHash] instead (ADR-0005 §4).
     */
    suspend fun addAuthTokenHash(hash: String)

    /** Forgets every phone-scoped pairing token hash, which revokes every paired phone. */
    suspend fun clearAuthTokenHashes()

    /**
     * Records one more bridge-scoped pairing token hash, keeping the ones already stored. Kept
     * apart from the phone hashes so a laptop bridge reaches only the bridge and log routes.
     */
    suspend fun addBridgeTokenHash(hash: String)

    /**
     * Records the name the laptop bridge gave when it paired, or clears it when [name] is null or
     * blank. Informational only: no token check reads it.
     */
    suspend fun setBridgeDeviceName(name: String?)

    /**
     * Forgets every bridge-scoped pairing token hash and the bridge's name ("Olvidar portátil",
     * ADR-0005 §4), which revokes every paired laptop bridge and leaves the phones paired.
     */
    suspend fun clearBridgeTokenHashes()

    suspend fun setFirstRunCompleted(done: Boolean)

    /** Turns on or off opening the app by itself when the TV powers on. */
    suspend fun setAutostartOnBoot(enabled: Boolean)

    /**
     * Stores the user's Anthropic API key for the translation provider, or clears it when [key] is
     * null or blank. Implementations never log the key.
     */
    suspend fun setTranslationApiKey(key: String?)
}
