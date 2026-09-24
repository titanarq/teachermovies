package com.teachermovies.storage

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

private const val PRIMARY_VOLUME_ID = "primary"

/**
 * [StorageVolumeProvider] over `Context.getExternalFilesDirs(null)` -- this app's own directory on
 * every currently attached volume, granted without any storage permission -- matched with
 * `StorageManager.getStorageVolume(File)` for the label, removable/primary flags and uuid.
 *
 * Never requests `MANAGE_EXTERNAL_STORAGE`: every root this returns is a directory the app already
 * owns, on internal storage or on removable media such as a USB drive or SD card.
 *
 * Runs only on a device (needs `Context`/`StorageManager`), so it has no JVM unit test; that it
 * compiles is what CI checks, behaviour on a real TV with a USB drive is a manual check (#43).
 */
class AndroidStorageVolumeProvider(
    private val context: Context,
) : StorageVolumeProvider {
    override fun volumes(): List<VolumeInfo> {
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        return context
            .getExternalFilesDirs(null)
            .filterNotNull()
            .mapNotNull { dir -> toVolumeInfo(dir, storageManager) }
    }

    private fun toVolumeInfo(
        dir: File,
        storageManager: StorageManager,
    ): VolumeInfo? {
        // A dir whose volume is currently unavailable has no StorageVolume to match it to.
        val volume: StorageVolume = storageManager.getStorageVolume(dir) ?: return null
        return VolumeInfo(
            id = volume.uuid ?: PRIMARY_VOLUME_ID,
            label = volume.getDescription(context),
            root = dir,
            removable = volume.isRemovable,
            primary = volume.isPrimary,
            mounted = volume.state == Environment.MEDIA_MOUNTED,
        )
    }
}
