package com.teachermovies.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class VolumeSelectorTest {
    @Test
    fun noVolumesIsNoneAvailableRegardlessOfPersistedId() {
        assertEquals(VolumeSelection.NoneAvailable, select(emptyList(), persistedId = null))
        assertEquals(VolumeSelection.NoneAvailable, select(emptyList(), persistedId = "usb-1"))
    }

    @Test
    fun persistedIdMountedIsSelected() {
        val primary = volume("primary", primary = true)
        val usb = volume("usb-1", removable = true)

        val selection = select(listOf(primary, usb), persistedId = "usb-1")

        assertEquals(VolumeSelection.Selected(usb), selection)
    }

    @Test
    fun persistedIdAbsentIsPersistedMissingWithFallback() {
        val primary = volume("primary", primary = true)
        val usb = volume("usb-1", removable = true)

        val selection = select(listOf(primary, usb), persistedId = "unplugged-usb")

        assertEquals(VolumeSelection.PersistedMissing("unplugged-usb", fallback = usb), selection)
    }

    @Test
    fun persistedIdUnmountedIsPersistedMissingWithFallback() {
        val primary = volume("primary", primary = true)
        val usb = volume("usb-1", removable = true, mounted = false)

        val selection = select(listOf(primary, usb), persistedId = "usb-1")

        assertEquals(VolumeSelection.PersistedMissing("usb-1", fallback = primary), selection)
    }

    @Test
    fun persistedIdMissingWithNothingToFallBackToIsPersistedMissingWithNullFallback() {
        val unmountedUsb = volume("usb-1", removable = true, mounted = false)

        val selection = select(listOf(unmountedUsb), persistedId = "gone")

        assertEquals(VolumeSelection.PersistedMissing("gone", fallback = null), selection)
    }

    @Test
    fun noPersistedIdPicksTheLargestFreeMountedRemovableVolume() {
        val primary = volume("primary", primary = true)
        val smallUsb = volume("usb-small", removable = true)
        val bigUsb = volume("usb-big", removable = true)
        val spaceProvider = FakeSpaceProvider(mapOf(primary.root to 999L, smallUsb.root to 10L, bigUsb.root to 100L))

        val selection = VolumeSelector.select(listOf(primary, smallUsb, bigUsb), persistedId = null, spaceProvider)

        assertEquals(VolumeSelection.Selected(bigUsb), selection)
    }

    @Test
    fun noPersistedIdIgnoresAnUnmountedRemovableVolumeEvenWithMoreFreeSpace() {
        val primary = volume("primary", primary = true)
        val unmountedUsb = volume("usb-1", removable = true, mounted = false)
        val spaceProvider = FakeSpaceProvider(mapOf(primary.root to 1L, unmountedUsb.root to 999L))

        val selection = VolumeSelector.select(listOf(primary, unmountedUsb), persistedId = null, spaceProvider)

        assertEquals(VolumeSelection.Selected(primary), selection)
    }

    @Test
    fun noPersistedIdFallsBackToPrimaryWhenNoRemovableVolumeIsMounted() {
        val primary = volume("primary", primary = true)

        val selection = select(listOf(primary), persistedId = null)

        assertEquals(VolumeSelection.Selected(primary), selection)
    }

    @Test
    fun noPersistedIdAndNothingAvailableIsNoneAvailable() {
        val onlyUnmounted = volume("usb-1", removable = true, mounted = false)

        val selection = select(listOf(onlyUnmounted), persistedId = null)

        assertEquals(VolumeSelection.NoneAvailable, selection)
    }

    /** [VolumeSelector.select] with a [FakeSpaceProvider] that reports no free space anywhere: for
     *  tests where free-space ordering is not what is under test. */
    private fun select(
        volumes: List<VolumeInfo>,
        persistedId: String?,
    ): VolumeSelection = VolumeSelector.select(volumes, persistedId, FakeSpaceProvider(emptyMap()))

    private fun volume(
        id: String,
        removable: Boolean = false,
        primary: Boolean = false,
        mounted: Boolean = true,
    ): VolumeInfo =
        VolumeInfo(
            id = id,
            label = id,
            root = File("/volumes/$id"),
            removable = removable,
            primary = primary,
            mounted = mounted,
        )

    /** [SpaceProvider] over a fixed map from a volume's root to its free space; 0 for anything else. */
    private class FakeSpaceProvider(
        private val freeBytesByRoot: Map<File, Long>,
    ) : SpaceProvider {
        override fun spaceOf(root: File): SpaceInfo =
            SpaceInfo(freeBytes = freeBytesByRoot[root] ?: 0L, totalBytes = 0L)
    }
}
