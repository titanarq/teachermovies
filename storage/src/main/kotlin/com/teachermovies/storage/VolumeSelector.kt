package com.teachermovies.storage

/**
 * Picks which [VolumeInfo] downloads go to, given the persisted choice
 * (`SettingsRepository.settings.downloadVolumeId`, #41) and the volumes
 * [StorageVolumeProvider.volumes] currently reports.
 */
object VolumeSelector {

    /**
     * @param volumes the volumes currently reported; empty when none is available at all.
     * @param persistedId the persisted `downloadVolumeId`, or null if the user never chose one.
     * @param spaceProvider where free space for the fallback rule comes from.
     *
     * Rules:
     * - [volumes] empty -> [VolumeSelection.NoneAvailable].
     * - [persistedId] names a volume in [volumes] that is mounted -> [VolumeSelection.Selected].
     * - [persistedId] set but absent from [volumes], or present but unmounted ->
     *   [VolumeSelection.PersistedMissing] carrying the same fallback as the rule below.
     * - [persistedId] null -> [VolumeSelection.Selected] with the fallback volume: the largest-free
     *   mounted removable volume, else the primary volume, else [VolumeSelection.NoneAvailable] if
     *   there is none.
     */
    fun select(
        volumes: List<VolumeInfo>,
        persistedId: String?,
        spaceProvider: SpaceProvider,
    ): VolumeSelection {
        if (volumes.isEmpty()) return VolumeSelection.NoneAvailable

        if (persistedId != null) {
            val persisted = volumes.find { it.id == persistedId }
            return if (persisted != null && persisted.mounted) {
                VolumeSelection.Selected(persisted)
            } else {
                VolumeSelection.PersistedMissing(persistedId, fallback(volumes, spaceProvider))
            }
        }

        val chosen = fallback(volumes, spaceProvider) ?: return VolumeSelection.NoneAvailable
        return VolumeSelection.Selected(chosen)
    }

    /** The largest-free mounted removable volume, else the primary volume, else null. */
    private fun fallback(volumes: List<VolumeInfo>, spaceProvider: SpaceProvider): VolumeInfo? {
        val largestRemovable =
            volumes.filter { it.removable && it.mounted }
                .maxByOrNull { spaceProvider.spaceOf(it.root).freeBytes }
        return largestRemovable ?: volumes.find { it.primary }
    }
}
