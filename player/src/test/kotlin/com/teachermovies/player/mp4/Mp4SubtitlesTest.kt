package com.teachermovies.player.mp4

import com.teachermovies.player.api.SubtitleExtraction
import com.teachermovies.player.api.SubtitleFormat
import com.teachermovies.player.mp4.Mp4Writer.Sample
import com.teachermovies.player.mp4.Mp4Writer.TrackSpec
import com.teachermovies.player.mp4.Mp4Writer.box
import com.teachermovies.player.mp4.Mp4Writer.gap
import com.teachermovies.player.mp4.Mp4Writer.mp4
import com.teachermovies.player.mp4.Mp4Writer.tx3g
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [Mp4Subtitles] against tiny MP4 files built in memory by [Mp4Writer]: the track list, exact SRT
 * timing, gaps, `co64`/`stz2`, non-text tracks, and every way a file can fail without leaving a
 * partial destination behind.
 */
class Mp4SubtitlesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val video = TrackSpec(1, "vide", "avc1", timescale = 90_000, samples = listOf(Sample(ByteArray(3_000) { 0x5A }, 3_000)))
    private val audio = TrackSpec(2, "soun", "mp4a", language = "spa", samples = listOf(Sample(ByteArray(500) { 0x33 }, 1_024)))

    private fun textTrack(
        vararg samples: Sample,
        timescale: Long = 1_000,
        co64: Boolean = false,
        compactSizes: Boolean = false,
        samplesPerChunk: Int = 1,
        handler: String = "sbtl",
    ) = TrackSpec(3, handler, "tx3g", timescale, samples.toList(), co64 = co64, compactSizes = compactSizes, samplesPerChunk = samplesPerChunk)

    private fun write(bytes: ByteArray): File = tmp.newFile().apply { writeBytes(bytes) }

    private val destination: File get() = tmp.root.resolve("out").apply { mkdirs() }.resolve("extracted.srt")

    @Test
    fun textTracksListsOnlyTheSubtitleTracksWithTheirLanguage() {
        val vobsub = TrackSpec(4, "subp", "mp4s", language = "fre")
        val file = write(mp4(listOf(video, audio, textTrack(tx3g("Hi", 1_000)), vobsub)))

        assertEquals(
            Mp4TracksResult.Tracks(listOf(Mp4TextTrack(3, "sbtl", "tx3g", "eng"), Mp4TextTrack(4, "subp", "mp4s", "fre"))),
            Mp4Subtitles.textTracks(file),
        )
    }

    @Test
    fun aFileThatDoesNotStartWithFtypIsNotMp4() {
        val mkv = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x80.toByte(), 0, 0, 0)

        assertEquals(Mp4TracksResult.NotMp4, Mp4Subtitles.textTracks(write(mkv)))
        assertEquals(Mp4TracksResult.NotMp4, Mp4Subtitles.textTracks(write(ByteArray(0))))
        assertEquals(SubtitleExtraction.Failed("not an MP4 file"), Mp4Subtitles.extract(write(mkv), 3, destination))
    }

    @Test
    fun srtKeepsExactTimingInTheTrackTimescaleAndSkipsGaps() {
        // 90 kHz: 1.5 s is 135 000 ticks; the gap sample holds the screen empty for 2 s.
        val track =
            textTrack(
                gap(135_000),
                tx3g("Hello there.", 108_000),
                gap(180_000),
                tx3g("Second\nline", 45_000, withStyleBox = true),
                tx3g("", 9_000),
                tx3g("Últimas palabras", 324_000_000L - 477_000),
                timescale = 90_000,
            )
        val file = write(mp4(listOf(video, audio, track)))
        val out = destination

        val result = Mp4Subtitles.extract(file, 3, out)

        assertEquals(SubtitleExtraction.Extracted(out, SubtitleFormat.SRT), result)
        assertEquals(
            """
            1
            00:00:01,500 --> 00:00:02,700
            Hello there.

            2
            00:00:04,700 --> 00:00:05,200
            Second
            line

            3
            00:00:05,300 --> 01:00:00,000
            Últimas palabras


            """.trimIndent(),
            out.readText(),
        )
        assertEquals(listOf("extracted.srt"), tmp.root.resolve("out").list()!!.toList())
    }

    @Test
    fun moovBeforeMdatSeveralSamplesPerChunkAndCompactSizesReadTheSameCues() {
        val track = textTrack(tx3g("One", 1_000), gap(500), tx3g("Two", 250), compactSizes = true, samplesPerChunk = 2)
        val file = write(mp4(listOf(video, track), moovFirst = true))
        val out = destination

        assertEquals(SubtitleExtraction.Extracted(out, SubtitleFormat.SRT), Mp4Subtitles.extract(file, 3, out))
        assertEquals(
            "1\n00:00:00,000 --> 00:00:01,000\nOne\n\n2\n00:00:01,500 --> 00:00:01,750\nTwo\n\n",
            out.readText(),
        )
    }

    @Test
    fun aCo64TableReachesSamplesPastFourGibibytes() {
        val track = textTrack(tx3g("Far away", 2_000), tx3g("Still far", 1_000), co64 = true)
        val base = 5L * 1024 * 1024 * 1024 + 17
        val file = write(mp4(listOf(track), sampleBase = base))
        // A sparse file: only the header and the few sample bytes past 5 GiB take space on disk.
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(base)
            raf.write(Mp4Writer.payload(listOf(track)))
        }
        val out = destination

        assertEquals(SubtitleExtraction.Extracted(out, SubtitleFormat.SRT), Mp4Subtitles.extract(file, 3, out))
        assertEquals(
            "1\n00:00:00,000 --> 00:00:02,000\nFar away\n\n2\n00:00:02,000 --> 00:00:03,000\nStill far\n\n",
            out.readText(),
        )
    }

    @Test
    fun aVersion1MdhdAndA64BitMdatHeaderAreRead() {
        val track = TrackSpec(3, "text", "tx3g", 600, listOf(tx3g("Six hundred", 900)), mdhdVersion1 = true)
        // A top-level box with a 64-bit largesize before mdat and moov.
        val file = write(mp4(listOf(track), afterFtyp = listOf(Mp4Writer.largeBox("free", ByteArray(3)))))
        val out = destination

        assertEquals(SubtitleExtraction.Extracted(out, SubtitleFormat.SRT), Mp4Subtitles.extract(file, 3, out))
        assertEquals("1\n00:00:00,000 --> 00:00:01,500\nSix hundred\n\n", out.readText())
    }

    @Test
    fun aNonTextTrackIsNotExtracted() {
        val vobsub = TrackSpec(4, "subp", "mp4s", samples = listOf(Sample(ByteArray(40), 1_000)))
        val webvtt = TrackSpec(5, "text", "wvtt", samples = listOf(Sample(ByteArray(40), 1_000)))
        val file = write(mp4(listOf(video, audio, vobsub, webvtt)))

        assertEquals(SubtitleExtraction.NotTextBased, Mp4Subtitles.extract(file, 4, destination))
        assertEquals(SubtitleExtraction.Failed("unsupported subtitle codec wvtt"), Mp4Subtitles.extract(file, 5, destination))
        assertEquals(SubtitleExtraction.TrackNotFound, Mp4Subtitles.extract(file, 1, destination))
        assertEquals(SubtitleExtraction.TrackNotFound, Mp4Subtitles.extract(file, 9, destination))
        assertFalse(destination.exists())
    }

    @Test
    fun aTrackOfOnlyGapsHasNoCues() {
        val file = write(mp4(listOf(textTrack(gap(1_000), tx3g("   ", 1_000)))))

        assertEquals(SubtitleExtraction.Failed("no cues"), Mp4Subtitles.extract(file, 3, destination))
        assertFalse(destination.exists())
    }

    @Test
    fun aFragmentedFileIsNotSupported() {
        val file = write(mp4(listOf(textTrack(tx3g("Hi", 1_000))), extraMoovBoxes = listOf(box("mvex"))))

        assertEquals(SubtitleExtraction.Failed("fragmented MP4 not supported"), Mp4Subtitles.extract(file, 3, destination))
    }

    @Test
    fun aFileCutInsideMoovIsTruncated() {
        val whole = mp4(listOf(video, textTrack(tx3g("Hi", 1_000))))
        val file = write(whole.copyOfRange(0, whole.size - 20))

        assertEquals(Mp4TracksResult.Failed("truncated file"), Mp4Subtitles.textTracks(file))
        assertEquals(SubtitleExtraction.Failed("truncated file"), Mp4Subtitles.extract(file, 3, destination))
        assertFalse(destination.exists())
    }

    @Test
    fun aFileCutInsideMdatIsTruncatedAndLeavesNoDestination() {
        // moov first, so the tables are whole but the last sample's bytes are missing.
        val whole = mp4(listOf(textTrack(tx3g("First", 1_000), tx3g("Second, never downloaded", 1_000))), moovFirst = true)
        val file = write(whole.copyOfRange(0, whole.size - 5))

        assertEquals(SubtitleExtraction.Failed("truncated file"), Mp4Subtitles.extract(file, 3, destination))
        assertFalse(destination.exists())
        assertEquals(listOf<String>(), tmp.root.resolve("out").list()!!.toList())
    }

    @Test
    fun aMissingMoovFails() {
        val file = write(Mp4Writer.concat(box("ftyp", "isom".toByteArray()), box("mdat", ByteArray(10))))

        assertEquals(SubtitleExtraction.Failed("no moov box"), Mp4Subtitles.extract(file, 3, destination))
    }
}
