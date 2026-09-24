package com.teachermovies.storage

import java.io.File

/**
 * One storage volume the app can download to: internal storage or a removable volume such as a
 * USB drive or SD card.
 *
 * [root] is this app's own directory on that volume -- what `Context.getExternalFilesDirs`
 * already grants without a storage permission -- not the volume's root, which the app has no
 * access to. [id] is the volume's uuid, or `"primary"` for the one volume that has none: stable
 * across the volume being unmounted and remounted, so it is what
 * `SettingsRepository.setDownloadVolumeId` persists (#41). [mounted] tells apart a volume that is
 * merely absent right now (a USB drive unplugged) from one that never existed; a removed volume
 * is a normal state, not a crash (AGENTS.md "Boundaries").
 */
data class VolumeInfo(
    val id: String,
    val label: String,
    val root: File,
    val removable: Boolean,
    val primary: Boolean,
    val mounted: Boolean,
)
