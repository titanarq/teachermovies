package com.teachermovies.torrent.resume

import com.teachermovies.core.model.TorrentId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileResumeDataStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val v1 = TorrentId("c9e15763f722f23e98a29decdfae341b98d53056")
    private val v2 = TorrentId("a".repeat(64))

    private val first = "d4:name5:movie6:pausedi0ee".toByteArray()
    private val second = "d4:name5:movie6:pausedi1e5:piecel1:a1:bee".toByteArray()

    private fun dir(): File = File(temp.root, "resume")

    private fun store() = FileResumeDataStore(dir())

    @Test
    fun savedDataRoundTrips() {
        store().save(v1, first)
        store().save(v2, second)

        val loaded = store().load()

        assertEquals(setOf(v1, v2), loaded.entries.keys)
        assertArrayEquals(first, loaded.entries.getValue(v1))
        assertArrayEquals(second, loaded.entries.getValue(v2))
        assertTrue(loaded.skipped.isEmpty())
        assertEquals(loaded.entries.keys, store().loadAll().keys)
    }

    @Test
    fun savesUnderTheInfoHashFileName() {
        store().save(v1, first)

        assertArrayEquals(first, File(dir(), "${v1.value}.fastresume").readBytes())
    }

    @Test
    fun saveOverwritesAtomicallyAndLeavesNoTempFile() {
        val store = store()
        store.save(v1, first)
        store.save(v1, second)

        assertArrayEquals(second, store.loadAll().getValue(v1))
        assertEquals(listOf("${v1.value}.fastresume"), dir().list()!!.toList())
    }

    @Test
    fun deleteRemovesTheEntryAndIsANoOpWhenAbsent() {
        val store = store()
        store.save(v1, first)
        store.save(v2, second)

        store.delete(v1)
        store.delete(v1)

        assertEquals(setOf(v2), store.loadAll().keys)
        assertFalse(File(dir(), "${v1.value}.fastresume").exists())
    }

    @Test
    fun corruptFilesAreSkippedAndReported() {
        val store = store()
        store.save(v1, first)
        val truncated = "b".repeat(40)
        val empty = "c".repeat(40)
        val garbage = "d".repeat(64)
        File(dir(), "$truncated.fastresume").writeBytes(first.copyOf(first.size - 3))
        File(dir(), "$empty.fastresume").writeBytes(ByteArray(0))
        File(dir(), "$garbage.fastresume").writeBytes("not bencode at all".toByteArray())

        val loaded = store.load()

        assertEquals(setOf(v1), loaded.entries.keys)
        assertEquals(
            setOf("$truncated.fastresume", "$empty.fastresume", "$garbage.fastresume"),
            loaded.skipped.map { it.name }.toSet(),
        )
    }

    @Test
    fun nonMatchingFileNamesAreIgnored() {
        val store = store()
        store.save(v1, first)
        File(dir(), "${v2.value}.fastresume.tmp").writeBytes(second)
        File(dir(), "${v1.value.uppercase()}.fastresume").writeBytes(second)
        File(dir(), "abc.fastresume").writeBytes(second)
        File(dir(), "${v2.value}.resume").writeBytes(second)
        File(dir(), "notes.txt").writeText("hello")
        File(dir(), "${"e".repeat(40)}.fastresume").mkdirs()

        val loaded = store.load()

        assertEquals(setOf(v1), loaded.entries.keys)
        assertTrue(loaded.skipped.isEmpty())
    }

    @Test
    fun missingDirectoryLoadsNothing() {
        val loaded = store().load()

        assertTrue(loaded.entries.isEmpty())
        assertTrue(loaded.skipped.isEmpty())
    }

    @Test
    fun bencodeCheckAcceptsOnlyOneWellFormedDictionary() {
        assertTrue(Bencode.isDictionary("de".toByteArray()))
        assertTrue(Bencode.isDictionary("d1:ad1:bli-3ei0e0:eee".toByteArray()))
        assertFalse(Bencode.isDictionary("le".toByteArray()))
        assertFalse(Bencode.isDictionary("d1:ae".toByteArray()))
        assertFalse(Bencode.isDictionary("di1ei2ee".toByteArray()))
        assertFalse(Bencode.isDictionary("d1:ai01ee".toByteArray()))
        assertFalse(Bencode.isDictionary("d1:a5:abce".toByteArray()))
        assertFalse(Bencode.isDictionary("dede".toByteArray()))
    }
}
