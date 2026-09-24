package com.teachermovies.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadLayoutTest {
    @get:Rule val tmpFolder = TemporaryFolder()

    private val layout get() = DownloadLayout(tmpFolder.root)

    @Test
    fun moviesDirIsMoviesUnderTheVolumeRoot() {
        assertEquals(File(tmpFolder.root, "Movies"), layout.moviesDir())
    }

    @Test
    fun torrentDirIsTheIdUnderMoviesDir() {
        val dir = layout.torrentDir(INFO_HASH)

        assertEquals(File(tmpFolder.root, "Movies/$INFO_HASH"), dir)
        assertEquals(File(layout.moviesDir(), INFO_HASH), dir)
    }

    @Test
    fun torrentDirAcceptsIdsThatAreOnlyNameCharacters() {
        for (id in listOf(INFO_HASH, "abc-123_XYZ", "A", "9", "v2-" + "f".repeat(64))) {
            assertEquals(id, layout.torrentDir(id).name)
        }
    }

    @Test
    fun torrentDirRejectsAnIdThatCouldNameAPath() {
        for (id in invalidIds) {
            val failure = runCatching { layout.torrentDir(id) }.exceptionOrNull()

            assertTrue(
                "id \"$id\" was accepted, expected an IllegalArgumentException",
                failure is IllegalArgumentException,
            )
        }
    }

    @Test
    fun ensureTorrentDirCreatesTheDirectoryAndItsParents() {
        val result = layout.ensureTorrentDir(INFO_HASH)

        val dir = result.getOrThrow()
        assertEquals(layout.torrentDir(INFO_HASH), dir)
        assertTrue("$dir is not a directory", dir.isDirectory)
        assertTrue("Movies was not created", layout.moviesDir().isDirectory)
    }

    @Test
    fun ensureTorrentDirSucceedsOnADirectoryThatAlreadyExists() {
        val first = layout.ensureTorrentDir(INFO_HASH).getOrThrow()

        val second = layout.ensureTorrentDir(INFO_HASH)

        assertEquals(first, second.getOrThrow())
        assertTrue(second.getOrThrow().isDirectory)
    }

    @Test
    fun ensureTorrentDirReportsAnInvalidIdAsAFailureAndCreatesNothing() {
        val result = layout.ensureTorrentDir("../escape")

        val failure = result.exceptionOrNull()
        assertTrue(
            "an invalid id was accepted, expected an IllegalArgumentException, was $failure",
            failure is IllegalArgumentException,
        )
        assertFalse("Movies was created anyway", layout.moviesDir().exists())
    }

    @Test
    fun ensureTorrentDirReportsAFileInTheWayAsAFailure() {
        // TemporaryFolder.newFile does not create parents, so Movies has to exist first.
        tmpFolder.newFolder("Movies")
        val blocking = tmpFolder.newFile("Movies/$INFO_HASH")

        val result = layout.ensureTorrentDir(INFO_HASH)

        val failure = result.exceptionOrNull()
        assertTrue(
            "a file in the way was accepted, expected an IllegalStateException, was $failure",
            failure is IllegalStateException,
        )
        assertTrue("$blocking stopped being a file", blocking.isFile)
    }

    @Test
    fun resolveInTorrentResolvesANameInsideTheTorrentDir() {
        val resolved = layout.resolveInTorrent(INFO_HASH, "movie.mkv")

        assertEquals(File(layout.torrentDir(INFO_HASH), "movie.mkv"), resolved)
    }

    @Test
    fun resolveInTorrentKeepsANestedRelativePath() {
        val resolved = layout.resolveInTorrent(INFO_HASH, "sub/dir/movie.en.srt")

        assertEquals(File(layout.torrentDir(INFO_HASH), "sub/dir/movie.en.srt"), resolved)
    }

    @Test
    fun resolveInTorrentCollapsesAnInnerParentSegmentThatStaysInside() {
        val resolved = layout.resolveInTorrent(INFO_HASH, "sub/../movie.mkv")

        assertEquals(File(layout.torrentDir(INFO_HASH), "movie.mkv"), resolved)
    }

    @Test
    fun resolveInTorrentRejectsAPathThatEscapesTheTorrentDir() {
        for (path in listOf("../x", "/etc/passwd", "a/../../b", "..", "../../etc/passwd")) {
            val failure = runCatching { layout.resolveInTorrent(INFO_HASH, path) }.exceptionOrNull()

            assertTrue(
                "path \"$path\" was accepted, expected an IllegalArgumentException, was $failure",
                failure is IllegalArgumentException,
            )
        }
    }

    @Test
    fun resolveInTorrentRejectsABlankRelativePath() {
        for (path in listOf("", " ", "\t")) {
            val failure = runCatching { layout.resolveInTorrent(INFO_HASH, path) }.exceptionOrNull()

            assertTrue(
                "path \"$path\" was accepted, expected an IllegalArgumentException, was $failure",
                failure is IllegalArgumentException,
            )
        }
    }

    @Test
    fun resolveInTorrentRejectsAnInvalidIdBeforeLookingAtThePath() {
        for (id in invalidIds) {
            val failure = runCatching { layout.resolveInTorrent(id, "movie.mkv") }.exceptionOrNull()

            assertTrue(
                "id \"$id\" was accepted, expected an IllegalArgumentException, was $failure",
                failure is IllegalArgumentException,
            )
        }
    }

    @Test
    fun fileSpaceProviderReportsTheSpaceOfTheTempDir() {
        val space = FileSpaceProvider().spaceOf(tmpFolder.root)

        assertTrue("free bytes was ${space.freeBytes}", space.freeBytes > 0L)
        assertTrue("total bytes was ${space.totalBytes}", space.totalBytes > 0L)
        assertTrue(
            "free ${space.freeBytes} exceeded total ${space.totalBytes}",
            space.totalBytes >= space.freeBytes,
        )
    }

    @Test
    fun fileSpaceProviderReportsNoSpaceForARootThatIsGone() {
        val space = FileSpaceProvider().spaceOf(File(tmpFolder.root, "unplugged"))

        assertEquals(SpaceInfo(freeBytes = 0L, totalBytes = 0L), space)
    }

    private companion object {
        /** A v1 info-hash: what `core-model`'s `TorrentId` accepts and what names a real download. */
        val INFO_HASH = "0123456789abcdef0123456789abcdef01234567"

        /** Blank, separators, parent segments and anything outside `[0-9A-Za-z_-]`. */
        val invalidIds =
            listOf("", " ", "\t", "/", "\\", "..", ".", "a/b", "a\\b", "a.b", "..hidden", "with space", "id;rm", "café")
    }
}
