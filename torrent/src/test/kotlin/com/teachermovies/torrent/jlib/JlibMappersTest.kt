package com.teachermovies.torrent.jlib

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.TorrentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JlibMappersTest {
    private val v1 = "c9e15763f722f23e98a29decdfae341b98d53056"
    private val v2 = "a".repeat(64)
    private val id = TorrentId(v1)

    @Test
    fun listenInterfacesCoverIpv4AndIpv6OnPort6881() {
        assertEquals("0.0.0.0:6881,[::]:6881", JlibMappers.listenInterfaces())
    }

    @Test
    fun torrentIdPrefersTheV1Hash() {
        assertEquals(TorrentId(v1), JlibMappers.torrentIdOf(v1, v2))
    }

    @Test
    fun torrentIdLowerCasesTheHash() {
        assertEquals(TorrentId(v1), JlibMappers.torrentIdOf(v1.uppercase(), null))
    }

    @Test
    fun torrentIdFallsBackToV2WhenV1IsAbsentOrAllZeros() {
        assertEquals(TorrentId(v2), JlibMappers.torrentIdOf(null, v2))
        assertEquals(TorrentId(v2), JlibMappers.torrentIdOf("0".repeat(40), v2))
    }

    @Test
    fun torrentIdIsNullWhenNoHashIsUsable() {
        assertNull(JlibMappers.torrentIdOf(null, null))
        assertNull(JlibMappers.torrentIdOf("0".repeat(40), "0".repeat(64)))
        assertNull(JlibMappers.torrentIdOf("xyz", v1))
    }

    @Test
    fun magnetSnapshotIsFetchingMetadataWithoutSizeOrPath() {
        val snapshot = JlibMappers.addedSnapshot(id, "Movie", hasMetadata = false, totalBytes = 99, savePath = "/x")

        assertEquals(DownloadState.FetchingMetadata, snapshot.state)
        assertEquals("Movie", snapshot.name)
        assertFalse(snapshot.hasMetadata)
        assertEquals(0L, snapshot.totalBytes)
        assertNull(snapshot.savePath)
        assertNull(snapshot.errorMessage)
    }

    @Test
    fun snapshotWithoutANameIsNamedAfterItsHash() {
        assertEquals(v1, JlibMappers.addedSnapshot(id, null, false, 0, null).name)
        assertEquals(v1, JlibMappers.addedSnapshot(id, " ", false, 0, null).name)
    }

    @Test
    fun torrentFileSnapshotIsQueuedWithItsMetadata() {
        val snapshot = JlibMappers.addedSnapshot(id, "Movie", hasMetadata = true, totalBytes = 1_000, savePath = "/movies/$v1")

        assertEquals(DownloadState.Queued, snapshot.state)
        assertTrue(snapshot.hasMetadata)
        assertEquals(1_000L, snapshot.totalBytes)
        assertEquals("/movies/$v1", snapshot.savePath)
    }

    @Test
    fun metadataMovesAMagnetToDownloadingWithNameSizeAndPath() {
        val pending = JlibMappers.addedSnapshot(id, null, false, 0, null)

        val updated = JlibMappers.withMetadata(pending, "Real Name", 4_096, "/movies/$v1")

        assertEquals(DownloadState.Downloading, updated.state)
        assertTrue(updated.hasMetadata)
        assertEquals("Real Name", updated.name)
        assertEquals(4_096L, updated.totalBytes)
        assertEquals("/movies/$v1", updated.savePath)
    }

    @Test
    fun metadataWithoutANameKeepsTheCurrentName() {
        val pending = JlibMappers.addedSnapshot(id, "From dn", false, 0, null)

        assertEquals("From dn", JlibMappers.withMetadata(pending, null, 1, null).name)
    }

    @Test
    fun metadataFollowsTheCoreModelTransitionTable() {
        // #145: Completed -> Downloading is legal (a skipped file un-skipped), and #159 dropped the
        // now-inert transition gate, so withMetadata sets Downloading directly.
        val completed = JlibMappers.addedSnapshot(id, "m", true, 1, "/p").copy(state = DownloadState.Completed)

        assertEquals(DownloadState.Downloading, JlibMappers.withMetadata(completed, "m", 1, "/p").state)
    }

    @Test
    fun addErrorMarksTheSnapshotErroredWithTheMessage() {
        val pending = JlibMappers.addedSnapshot(id, null, false, 0, null)

        val failed = JlibMappers.withAddError(pending, "disk full")

        assertEquals(DownloadState.Error, failed.state)
        assertEquals("disk full", failed.errorMessage)
    }
}
