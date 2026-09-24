package com.teachermovies.player.vlc

import com.teachermovies.player.api.SubtitleExtraction
import com.teachermovies.player.api.SubtitleFormat
import com.teachermovies.player.api.Track
import com.teachermovies.player.mkv.EbmlWriter
import com.teachermovies.player.mp4.Mp4Writer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [EmbeddedSubtitles]: container sniffing and the dispatch of a libVLC track id to its reader. */
class EmbeddedSubtitlesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(bytes: ByteArray): File = tmp.newFile().apply { writeBytes(bytes) }

    private val destination: File get() = tmp.root.resolve("extracted.srt")

    private val mkv =
        EbmlWriter.mkv(
            listOf(EbmlWriter.trackEntry(3, EbmlWriter.TYPE_SUBTITLE, "S_TEXT/WEBVTT")),
            EbmlWriter.cluster(0, EbmlWriter.blockGroup(3, 500, "<i>From MKV</i>", duration = 1_000)),
        )

    private val mp4 =
        Mp4Writer.mp4(
            listOf(
                Mp4Writer.TrackSpec(1, "vide", "avc1", samples = listOf(Mp4Writer.Sample(ByteArray(100), 1_000))),
                Mp4Writer.TrackSpec(
                    2,
                    "sbtl",
                    "tx3g",
                    samples = listOf(Mp4Writer.gap(250), Mp4Writer.tx3g("From MP4", 750)),
                ),
            ),
        )

    @Test
    fun theContainerIsSniffedFromTheFirstBytes() {
        assertEquals(EmbeddedSubtitles.Container.MATROSKA, EmbeddedSubtitles.containerOf(write(mkv)))
        assertEquals(EmbeddedSubtitles.Container.MP4, EmbeddedSubtitles.containerOf(write(mp4)))
        assertEquals(
            EmbeddedSubtitles.Container.OTHER,
            EmbeddedSubtitles.containerOf(write("RIFF....AVI LIST".toByteArray())),
        )
        assertEquals(EmbeddedSubtitles.Container.OTHER, EmbeddedSubtitles.containerOf(write(ByteArray(3))))
        assertEquals(EmbeddedSubtitles.Container.OTHER, EmbeddedSubtitles.containerOf(tmp.root.resolve("missing")))
    }

    @Test
    fun aMatroskaWebVttTrackIsExtractedByItsLibVlcId() {
        val out = destination

        val result = EmbeddedSubtitles.extract(write(mkv), "3", listOf(Track("3", "Track 1", null)), out)

        assertEquals(SubtitleExtraction.Extracted(out, SubtitleFormat.SRT), result)
        assertEquals("1\n00:00:00,500 --> 00:00:01,500\n<i>From MKV</i>\n\n", out.readText())
    }

    @Test
    fun anMp4Tx3gTrackIsExtractedByItsLibVlcId() {
        val out = destination

        val result = EmbeddedSubtitles.extract(write(mp4), "2", listOf(Track("2", "Track 1 - [English]", "en")), out)

        assertEquals(SubtitleExtraction.Extracted(out, SubtitleFormat.SRT), result)
        assertEquals("1\n00:00:00,250 --> 00:00:01,000\nFrom MP4\n\n", out.readText())
    }

    @Test
    fun anMp4TrackIdThatIsNotATrackNumberMapsByPosition() {
        val out = destination
        val tracks = listOf(Track("7", "Track 1", null), Track("8", "external.srt", null))

        assertEquals(
            SubtitleExtraction.Extracted(out, SubtitleFormat.SRT),
            EmbeddedSubtitles.extract(write(mp4), "7", tracks, out),
        )
        // The track after the embedded ones is an external slave: nothing to extract.
        assertEquals(SubtitleExtraction.TrackNotFound, EmbeddedSubtitles.extract(write(mp4), "8", tracks, out))
    }

    @Test
    fun anyOtherContainerIsNotSupported() {
        val avi = write("RIFF\u0000\u0000\u0000\u0000AVI LIST".toByteArray())
        val webmLike = write(EbmlWriter.mkv(listOf(), docType = "notmatroska"))
        val tracks = listOf(Track("3", "Track 1", null))

        assertEquals(
            SubtitleExtraction.Failed("container not supported"),
            EmbeddedSubtitles.extract(avi, "3", tracks, destination),
        )
        assertEquals(
            SubtitleExtraction.Failed("container not supported"),
            EmbeddedSubtitles.extract(webmLike, "3", tracks, destination),
        )
        assertFalse(destination.exists())
    }

    @Test
    fun anIdLibVlcDoesNotListIsNotFound() {
        assertEquals(
            SubtitleExtraction.TrackNotFound,
            EmbeddedSubtitles.extract(write(mp4), "2", emptyList(), destination),
        )
    }
}
