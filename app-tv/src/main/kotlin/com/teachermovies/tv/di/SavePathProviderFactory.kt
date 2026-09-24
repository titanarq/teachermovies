package com.teachermovies.tv.di

import com.teachermovies.storage.DownloadLayout
import com.teachermovies.storage.SpaceProvider
import com.teachermovies.storage.StorageVolumeProvider
import com.teachermovies.storage.VolumeSelection
import com.teachermovies.storage.VolumeSelector
import com.teachermovies.torrent.api.SavePathProvider
import java.io.File

/**
 * Builds the [SavePathProvider] the torrent engine is given: `<selectedVolume.root>/Movies/<id>`
 * (`DownloadLayout.torrentDir`), with the volume chosen by [VolumeSelector] from the volumes
 * reported right now and the persisted `downloadVolumeId`. `:torrent` never sees `:storage`; this
 * is the bridge between them.
 *
 * The volume is resolved on every call, so a volume picked in Settings (or one unplugged) applies
 * to the next torrent added without restarting the engine. A persisted volume that is missing
 * falls back to [VolumeSelection.PersistedMissing.fallback]; with no volume at all, [fallbackRoot]
 * (the app's internal storage) is used, so the provider never throws for a valid id.
 */
object SavePathProviderFactory {
    /**
     * @param volumes the volumes currently available.
     * @param space free space, for the selector's fallback rule.
     * @param persistedVolumeId reads the persisted `downloadVolumeId` (null = never chosen).
     * @param fallbackRoot the root used when no volume is available at all.
     */
    fun create(
        volumes: StorageVolumeProvider,
        space: SpaceProvider,
        persistedVolumeId: () -> String?,
        fallbackRoot: File,
    ): SavePathProvider =
        SavePathProvider { id ->
            val root =
                when (val selection = VolumeSelector.select(volumes.volumes(), persistedVolumeId(), space)) {
                    is VolumeSelection.Selected -> selection.volume.root
                    is VolumeSelection.PersistedMissing -> selection.fallback?.root ?: fallbackRoot
                    VolumeSelection.NoneAvailable -> fallbackRoot
                }
            DownloadLayout(root).torrentDir(id.value)
        }
}
