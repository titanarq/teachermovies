package com.teachermovies.tv.di

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.TorrentId
import com.teachermovies.storage.DownloadLayout
import com.teachermovies.torrent.api.TorrentSnapshot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleLayoutResolverTest {

    private val id = "a".repeat(40)
    private val usbRoot = File("/volumes/USB-1")

    @Test
    fun layoutIsTheVolumeTheTorrentIsSavedOn() {
        val savePath = DownloadLayout(usbRoot).torrentDir(id).path

        val layout = SubtitleLayoutResolver.layoutFor(id, listOf(snapshot(id, savePath)))

        assertEquals(DownloadLayout(usbRoot).torrentDir(id), layout?.torrentDir(id))
    }

    @Test
    fun unknownTorrentHasNoLayout() {
        val savePath = DownloadLayout(usbRoot).torrentDir(id).path

        assertNull(SubtitleLayoutResolver.layoutFor("b".repeat(40), listOf(snapshot(id, savePath))))
    }

    @Test
    fun torrentWithoutSavePathHasNoLayout() {
        assertNull(SubtitleLayoutResolver.layoutFor(id, listOf(snapshot(id, savePath = null))))
    }

    @Test
    fun savePathOutsideTheMoviesLayoutHasNoLayout() {
        assertNull(SubtitleLayoutResolver.layoutFor(id, listOf(snapshot(id, "/volumes/USB-1/Other/$id"))))
        assertNull(SubtitleLayoutResolver.layoutFor(id, listOf(snapshot(id, "/volumes/USB-1/Movies/other"))))
    }

    private fun snapshot(id: String, savePath: String?) =
        TorrentSnapshot(
            id = TorrentId(id),
            name = "Movie",
            state = DownloadState.Downloading,
            progressPercent = 0.0,
            downloadedBytes = 0,
            totalBytes = 0,
            downloadRateBps = 0,
            uploadRateBps = 0,
            peers = 0,
            etaSeconds = null,
            ratio = 0.0,
            hasMetadata = savePath != null,
            savePath = savePath,
            errorMessage = null,
        )
}
