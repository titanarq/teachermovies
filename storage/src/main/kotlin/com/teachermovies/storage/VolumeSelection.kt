package com.teachermovies.storage

/**
 * What `VolumeSelector.select` decided, so a caller (the picker UI of #45, the wiring that hands
 * `DownloadLayout` its root) reacts to a persisted choice going away without treating it as an
 * error: a removed volume is a normal state (AGENTS.md "Boundaries").
 */
sealed interface VolumeSelection {
    /** [volume] is the one downloads go to. */
    data class Selected(val volume: VolumeInfo) : VolumeSelection

    /**
     * [persistedId] -- the id `SettingsRepository.settings` last had for `downloadVolumeId` -- does
     * not name a mounted volume right now, e.g. a USB drive unplugged since the last choice.
     * [fallback] is the volume selection would use instead, by the same rule as no persisted id at
     * all, or null if nothing is available to fall back to.
     */
    data class PersistedMissing(val persistedId: String, val fallback: VolumeInfo?) : VolumeSelection

    /** No volume is available at all: [StorageVolumeProvider.volumes] reported none. */
    data object NoneAvailable : VolumeSelection
}
