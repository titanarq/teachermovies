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

    /** Records one more pairing token hash, keeping the ones already stored. */
    suspend fun addAuthTokenHash(hash: String)

    /** Forgets every pairing token hash, which revokes every paired phone. */
    suspend fun clearAuthTokenHashes()

    suspend fun setFirstRunCompleted(done: Boolean)
}
