package com.teachermovies.torrent.service

import android.content.pm.ServiceInfo
import android.os.Build

/**
 * The foreground-service type [TorrentService] goes to the foreground with, chosen from the API
 * level it runs on. Pure and free of Android runtime dependencies, so a JVM test covers it (#129).
 *
 * - API 34+: `specialUse`. Android 15 caps `dataSync` at 6 h per 24 h and refuses to start a
 *   `dataSync` service from `BOOT_COMPLETED`; downloads the user started must survive both, and the
 *   justification for the type is the manifest's `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`.
 * - API 29..33: `dataSync`, the closest declared type before `specialUse` existed, and still the
 *   one the manifest's `FOREGROUND_SERVICE_DATA_SYNC` permission backs.
 * - below API 29: `0`, because typed foreground services start at API 29.
 */
object ForegroundServiceTypes {
    fun forSdk(sdkInt: Int): Int =
        when {
            sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            sdkInt >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
}
