package com.teachermovies.core.settings

internal const val DEFAULT_HTTP_PORT = 8787

/**
 * Everything the app persists as a setting, read through [SettingsRepository].
 *
 * [httpPort] is the port the embedded HTTP server binds on the LAN (ADR-0002).
 * [downloadVolumeId] is the id of the storage volume downloads go to, null while the app has not
 * chosen one. [authTokenHashes] holds a hash of every bearer token PIN pairing has issued, never a
 * token itself. [firstRunCompleted] is false until the first-run setup has finished, which is what
 * makes the app show it again after a fresh install.
 */
data class AppSettings(
    val httpPort: Int = DEFAULT_HTTP_PORT,
    val downloadVolumeId: String? = null,
    val authTokenHashes: Set<String> = emptySet(),
    val firstRunCompleted: Boolean = false,
)
