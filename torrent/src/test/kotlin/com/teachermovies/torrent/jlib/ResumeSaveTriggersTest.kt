package com.teachermovies.torrent.jlib

import com.teachermovies.core.model.TorrentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumeSaveTriggersTest {
    private val id = TorrentId("dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c")
    private val other = TorrentId("08ada5a7a6183aae1e09d831df6748d566095a10")

    @Test
    fun finishedTorrentIsSavedAtOnce() {
        assertEquals(id, ResumeSaveTriggers.torrentToSave(SessionEvent.Finished(id), setOf(id, other)))
    }

    @Test
    fun finishedAlertForARemovedTorrentSavesNothing() {
        assertNull(ResumeSaveTriggers.torrentToSave(SessionEvent.Finished(id), setOf(other)))
    }

    @Test
    fun finishedAlertWithoutAnIdSavesNothing() {
        assertNull(ResumeSaveTriggers.torrentToSave(SessionEvent.Finished(null), setOf(id)))
    }

    @Test
    fun otherEventsLeaveSavingToTheirOwnPaths() {
        val known = setOf(id)
        val events =
            listOf(
                SessionEvent.ResumeDataSaved(id, byteArrayOf(1)),
                SessionEvent.ResumeDataFailed(id),
                SessionEvent.FilesDeleted(id),
                SessionEvent.FilesDeleteFailed(id),
            )
        for (event in events) assertNull(event.toString(), ResumeSaveTriggers.torrentToSave(event, known))
    }
}
