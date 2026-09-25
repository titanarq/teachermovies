package com.teachermovies.torrent.jlib

import com.teachermovies.core.model.TorrentId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TorrentDirCleanupTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val id = TorrentId("dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c")

    private fun torrentDir(name: String = id.value): File = File(temp.root, "Movies/$name").also { it.mkdirs() }

    @Test
    fun emptyDirIsRemovedOnceFilesAreDeleted() {
        val dir = torrentDir()
        val cleanup = TorrentDirCleanup()

        cleanup.removeRequested(id, dir, deleteFiles = true)
        assertTrue("kept until libtorrent confirms the delete", dir.isDirectory)

        assertTrue(cleanup.filesDeleted(id))
        assertFalse(dir.exists())
        assertTrue("the Movies folder itself stays", dir.parentFile.isDirectory)
    }

    @Test
    fun nonEmptyDirIsKept() {
        val dir = torrentDir()
        val subtitle = File(dir, "subs/movie.en.srt").also { it.parentFile.mkdirs() }
        subtitle.writeText("1\n00:00:01,000 --> 00:00:02,000\nHi\n")
        val cleanup = TorrentDirCleanup()

        cleanup.removeRequested(id, dir, deleteFiles = true)

        assertFalse(cleanup.filesDeleted(id))
        assertTrue(subtitle.isFile)
    }

    @Test
    fun removeWithoutDeleteFilesKeepsDir() {
        val dir = torrentDir()
        val cleanup = TorrentDirCleanup()

        cleanup.removeRequested(id, dir, deleteFiles = false)

        assertFalse(cleanup.filesDeleted(id))
        assertTrue(dir.isDirectory)
    }

    @Test
    fun failedDeleteKeepsDir() {
        val dir = torrentDir()
        val cleanup = TorrentDirCleanup()

        cleanup.removeRequested(id, dir, deleteFiles = true)
        cleanup.deleteFailed(id)

        assertFalse(cleanup.filesDeleted(id))
        assertTrue(dir.isDirectory)
    }

    @Test
    fun dirNotNamedAfterTheTorrentIsNeverRemoved() {
        val shared = torrentDir(name = "shared")
        val cleanup = TorrentDirCleanup()

        cleanup.removeRequested(id, shared, deleteFiles = true)

        assertFalse(cleanup.filesDeleted(id))
        assertTrue(shared.isDirectory)
    }

    @Test
    fun deletedAlertForAnUnknownTorrentDoesNothing() {
        val dir = torrentDir()

        assertFalse(TorrentDirCleanup().filesDeleted(id))
        assertTrue(dir.isDirectory)
    }
}
