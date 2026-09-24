package com.teachermovies.tv.di

import com.teachermovies.core.model.TorrentId
import com.teachermovies.storage.SpaceInfo
import com.teachermovies.storage.SpaceProvider
import com.teachermovies.storage.StorageVolumeProvider
import com.teachermovies.storage.VolumeInfo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class SavePathProviderFactoryTest {

    private val hash = TorrentId("0123456789abcdef0123456789abcdef01234567")
    private val fallbackRoot = File("/data/app/files")

    @Test
    fun returnsMoviesDirOfThePersistedSelectedVolume() {
        val provider =
            SavePathProviderFactory.create(
                volumes = FakeVolumeProvider(listOf(volume("primary", primary = true), volume("usb", removable = true))),
                space = FakeSpaceProvider,
                persistedVolumeId = { "primary" },
                fallbackRoot = fallbackRoot,
            )

        assertEquals(File("/volumes/primary/Movies/${hash.value}"), provider.savePathFor(hash))
    }

    @Test
    fun withNoPersistedChoiceUsesTheSelectorsFallbackVolume() {
        val provider =
            SavePathProviderFactory.create(
                volumes = FakeVolumeProvider(listOf(volume("primary", primary = true), volume("usb", removable = true))),
                space = FakeSpaceProvider,
                persistedVolumeId = { null },
                fallbackRoot = fallbackRoot,
            )

        assertEquals(File("/volumes/usb/Movies/${hash.value}"), provider.savePathFor(hash))
    }

    @Test
    fun aMissingPersistedVolumeFallsBackWithoutThrowing() {
        val provider =
            SavePathProviderFactory.create(
                volumes = FakeVolumeProvider(listOf(volume("primary", primary = true))),
                space = FakeSpaceProvider,
                persistedVolumeId = { "unplugged-usb" },
                fallbackRoot = fallbackRoot,
            )

        assertEquals(File("/volumes/primary/Movies/${hash.value}"), provider.savePathFor(hash))
    }

    @Test
    fun noVolumeAtAllUsesTheFallbackRoot() {
        val provider =
            SavePathProviderFactory.create(
                volumes = FakeVolumeProvider(emptyList()),
                space = FakeSpaceProvider,
                persistedVolumeId = { null },
                fallbackRoot = fallbackRoot,
            )

        assertEquals(File(fallbackRoot, "Movies/${hash.value}"), provider.savePathFor(hash))
    }

    @Test
    fun theVolumeIsResolvedOnEveryCall() {
        var persisted: String? = "primary"
        val provider =
            SavePathProviderFactory.create(
                volumes = FakeVolumeProvider(listOf(volume("primary", primary = true), volume("usb", removable = true))),
                space = FakeSpaceProvider,
                persistedVolumeId = { persisted },
                fallbackRoot = fallbackRoot,
            )
        provider.savePathFor(hash)

        persisted = "usb"

        assertEquals(File("/volumes/usb/Movies/${hash.value}"), provider.savePathFor(hash))
    }

    private fun volume(
        id: String,
        removable: Boolean = false,
        primary: Boolean = false,
    ) = VolumeInfo(
        id = id,
        label = id,
        root = File("/volumes/$id"),
        removable = removable,
        primary = primary,
        mounted = true,
    )

    private class FakeVolumeProvider(private val current: List<VolumeInfo>) : StorageVolumeProvider {
        override fun volumes(): List<VolumeInfo> = current
    }

    private object FakeSpaceProvider : SpaceProvider {
        override fun spaceOf(root: File): SpaceInfo = SpaceInfo(freeBytes = 1, totalBytes = 1)
    }
}
