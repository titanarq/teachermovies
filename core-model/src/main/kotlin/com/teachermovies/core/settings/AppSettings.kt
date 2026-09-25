package com.teachermovies.core.settings

internal const val DEFAULT_HTTP_PORT = 8787

/**
 * Everything the app persists as a setting, read through [SettingsRepository].
 *
 * [httpPort] is the port the embedded HTTP server binds on the LAN (ADR-0002).
 * [downloadVolumeId] is the id of the storage volume downloads go to, null while the app has not
 * chosen one. [authTokenHashes] holds a hash of every bearer token PIN pairing has issued, never a
 * token itself. [firstRunCompleted] is false until the first-run setup has finished, which is what
 * makes the app show it again after a fresh install. [autostartOnBoot] is whether the app opens by
 * itself when the TV powers on, off until the user turns it on. [translationApiKey] is the user's own
 * Anthropic API key for the EN->ES translation provider, pasted in Configuración and never compiled
 * into the APK; null while none is set. [toString] redacts it, so logging an [AppSettings] never
 * prints the key.
 */
data class AppSettings(
    val httpPort: Int = DEFAULT_HTTP_PORT,
    val downloadVolumeId: String? = null,
    val authTokenHashes: Set<String> = emptySet(),
    val firstRunCompleted: Boolean = false,
    val autostartOnBoot: Boolean = false,
    val translationApiKey: String? = null,
) {
    override fun toString(): String =
        "AppSettings(httpPort=$httpPort, downloadVolumeId=$downloadVolumeId, " +
            "authTokenHashes=$authTokenHashes, firstRunCompleted=$firstRunCompleted, " +
            "autostartOnBoot=$autostartOnBoot, " +
            "translationApiKey=${if (translationApiKey == null) "null" else "<redacted>"})"
}
