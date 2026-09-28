package com.teachermovies.player.api

import com.teachermovies.player.mkv.EbmlWriter
import com.teachermovies.player.mp4.Mp4Writer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [EmbeddedTextTracks] against the tiny containers [EbmlWriter] and [Mp4Writer] build in memory:
 * the language of every text subtitle track each reader reports, image-based tracks skipped, and
 * every file that has to answer an empty list rather than throw.
 */
class EmbeddedTextTracksTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(
        name: String,
        bytes: ByteArray,
    ): File = tmp.root.resolve(name).apply { writeBytes(bytes) }

    private val mkvVideo = EbmlWriter.trackEntry(1, EbmlWriter.TYPE_VIDEO, "V_MPEG4/ISO/AVC", language = "und")
    private val mkvAudio = EbmlWriter.trackEntry(2, EbmlWriter.TYPE_AUDIO, "A_AAC", language = "eng")
    private val mkvSrt = EbmlWriter.trackEntry(3, EbmlWriter.TYPE_SUBTITLE, "S_TEXT/UTF8", language = "eng")
    private val mkvAss =
        EbmlWriter.trackEntry(4, EbmlWriter.TYPE_SUBTITLE, "S_TEXT/ASS", language = "spa", languageIetf = "es-ES")

    private val mkvPgs = EbmlWriter.trackEntry(5, EbmlWriter.TYPE_SUBTITLE, "S_HDMV/PGS", language = "eng")
    private val mkvVobSub = EbmlWriter.trackEntry(6, EbmlWriter.TYPE_SUBTITLE, "S_VOBSUB", language = "eng")
    private val mkvSpanishSrt = EbmlWriter.trackEntry(7, EbmlWriter.TYPE_SUBTITLE, "S_TEXT/UTF8", language = "spa")

    private val mp4Video =
        Mp4Writer.TrackSpec(1, "vide", "avc1", samples = listOf(Mp4Writer.Sample(ByteArray(100), 1_000)))
    private val mp4Audio = Mp4Writer.TrackSpec(2, "soun", "mp4a", language = "spa")
    private val mp4English =
        Mp4Writer.TrackSpec(3, "sbtl", "tx3g", language = "eng", samples = listOf(Mp4Writer.tx3g("Hi", 1_000)))
    private val mp4Spanish =
        Mp4Writer.TrackSpec(4, "sbtl", "tx3g", language = "spa", samples = listOf(Mp4Writer.tx3g("Hola", 1_000)))

    @Test
    fun aMatroskaFileReportsTheLanguageOfEverySubtitleTrack() {
        val file = write("movie.mkv", EbmlWriter.mkv(listOf(mkvVideo, mkvAudio, mkvSrt, mkvAss)))

        // The video and audio tracks are not listed, and track 4's LanguageIETF wins over the
        // Language ("spa") it also carries -- the precedence Matroska defines.
        assertEquals(listOf("eng", "es-ES"), EmbeddedTextTracks.languagesOf(file))
    }

    @Test
    fun anMp4FileReportsTheLanguageOfEverySubtitleTrack() {
        val file = write("movie.mp4", Mp4Writer.mp4(listOf(mp4Video, mp4Audio, mp4English, mp4Spanish)))

        assertEquals(listOf("eng", "spa"), EmbeddedTextTracks.languagesOf(file))
    }

    @Test
    fun aMatroskaFileWhoseOnlySubtitleIsPgsHasNoLanguages() {
        val file = write("bluray.mkv", EbmlWriter.mkv(listOf(mkvVideo, mkvAudio, mkvPgs)))

        // An image-based track cannot be read as text, so it counts as no English subtitle at all.
        assertEquals(emptyList<String>(), EmbeddedTextTracks.languagesOf(file))
    }

    @Test
    fun aMatroskaFileReportsItsTextTrackButNotItsVobSubTrack() {
        val file = write("dvd.mkv", EbmlWriter.mkv(listOf(mkvVideo, mkvAudio, mkvVobSub, mkvSpanishSrt)))

        assertEquals(listOf("spa"), EmbeddedTextTracks.languagesOf(file))
    }

    @Test
    fun anMp4FileReportsOnlyItsTx3gTracks() {
        val vobSub = Mp4Writer.TrackSpec(5, "subp", "mp4s", language = "eng")
        val file = write("dvd.mp4", Mp4Writer.mp4(listOf(mp4Video, mp4Audio, vobSub, mp4Spanish)))

        assertEquals(listOf("spa"), EmbeddedTextTracks.languagesOf(file))
    }

    @Test
    fun aContainerWithoutASubtitleTrackHasNoLanguages() {
        val mkv = write("silent.mkv", EbmlWriter.mkv(listOf(mkvVideo, mkvAudio)))
        val mp4 = write("silent.mp4", Mp4Writer.mp4(listOf(mp4Video, mp4Audio)))

        assertEquals(emptyList<String>(), EmbeddedTextTracks.languagesOf(mkv))
        assertEquals(emptyList<String>(), EmbeddedTextTracks.languagesOf(mp4))
    }

    @Test
    fun aFileThatIsNotAContainerHasNoLanguages() {
        val notes = write("notes.txt", "No EBML magic and no ftyp box here, just text.\n".toByteArray())

        assertEquals(emptyList<String>(), EmbeddedTextTracks.languagesOf(notes))
    }

    @Test
    fun aTruncatedContainerHasNoLanguages() {
        val whole = Mp4Writer.mp4(listOf(mp4English, mp4Spanish))
        val cut = write("partial.mp4", whole.copyOfRange(0, whole.size - 20))

        assertEquals(emptyList<String>(), EmbeddedTextTracks.languagesOf(cut))
    }

    @Test
    fun aMissingFileHasNoLanguages() {
        val gone = tmp.root.resolve("not-downloaded/movie.mkv")

        assertEquals(emptyList<String>(), EmbeddedTextTracks.languagesOf(gone))
    }
}
