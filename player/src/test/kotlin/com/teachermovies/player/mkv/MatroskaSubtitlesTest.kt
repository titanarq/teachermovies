package com.teachermovies.player.mkv

import com.teachermovies.player.api.SubtitleExtraction
import com.teachermovies.player.api.SubtitleFormat
import com.teachermovies.player.mkv.EbmlWriter.TYPE_AUDIO
import com.teachermovies.player.mkv.EbmlWriter.TYPE_SUBTITLE
import com.teachermovies.player.mkv.EbmlWriter.TYPE_VIDEO
import com.teachermovies.player.mkv.EbmlWriter.blockGroup
import com.teachermovies.player.mkv.EbmlWriter.cluster
import com.teachermovies.player.mkv.EbmlWriter.compression
import com.teachermovies.player.mkv.EbmlWriter.mkv
import com.teachermovies.player.mkv.EbmlWriter.simpleBlock
import com.teachermovies.player.mkv.EbmlWriter.trackEntry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.Deflater

/**
 * [MatroskaSubtitles] against tiny Matroska files built in memory by [EbmlWriter]: the track list,
 * exact SRT/ASS timing, content encodings, and every way a file can fail without leaving a partial
 * destination behind.
 */
class MatroskaSubtitlesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val video = trackEntry(1, TYPE_VIDEO, "V_MPEG4/ISO/AVC", language = "und")
    private val audio = trackEntry(2, TYPE_AUDIO, "A_AAC", language = "eng")
    private val srtTrack = trackEntry(3, TYPE_SUBTITLE, "S_TEXT/UTF8", language = "eng", name = "English")

    private fun videoFrame(relative: Int) = simpleBlock(1, relative, ByteArray(4_000) { 0x5A })

    private fun audioFrame(relative: Int) = simpleBlock(2, relative, ByteArray(700) { 0x33 })

    private fun write(bytes: ByteArray): File = tmp.newFile().apply { writeBytes(bytes) }

    private val destination: File get() =
        tmp.root
            .resolve("out")
            .apply { mkdirs() }
            .resolve("extracted.sub")

    @Test
    fun textTracksListsOnlyTheSubtitleTracksWithTheirMetadata() {
        val header = "[Script Info]\n".toByteArray()
        val file =
            write(
                mkv(
                    listOf(
                        video,
                        audio,
                        srtTrack,
                        trackEntry(
                            4,
                            TYPE_SUBTITLE,
                            "S_TEXT/ASS",
                            language = "spa",
                            languageIetf = "es-ES",
                            codecPrivate = header,
                        ),
                    ),
                    cluster(0, videoFrame(0)),
                ),
            )

        val result = MatroskaSubtitles.textTracks(file)

        assertEquals(
            MkvTracksResult.Tracks(
                listOf(
                    MkvTextTrack(3, "S_TEXT/UTF8", "eng", null, "English", null),
                    MkvTextTrack(4, "S_TEXT/ASS", "spa", "es-ES", null, header),
                ),
            ),
            result,
        )
    }

    @Test
    fun textTracksDefaultsTheLanguageToEnglishAndReportsAnMp4AsNotMatroska() {
        val file =
            write(mkv(listOf(trackEntry(5, TYPE_SUBTITLE, "S_TEXT/UTF8")), timecodeScale = 1_000_000, docType = "webm"))

        assertEquals(
            MkvTracksResult.Tracks(listOf(MkvTextTrack(5, "S_TEXT/UTF8", "eng", null, null, null))),
            MatroskaSubtitles.textTracks(file),
        )
        assertEquals(MkvTracksResult.NotMatroska, MatroskaSubtitles.textTracks(write(mp4Header())))
    }

    @Test
    fun srtKeepsExactTimingUnderANonDefaultTimecodeScaleAcrossClusters() {
        // 100 µs per tick: Cluster timecodes and block offsets are in tenths of a millisecond.
        val file =
            write(
                mkv(
                    listOf(video, audio, srtTrack),
                    cluster(
                        10_000,
                        videoFrame(0),
                        blockGroup(3, 5_000, "Hello there.", duration = 12_000),
                        videoFrame(100),
                        audioFrame(200),
                    ),
                    cluster(
                        36_000_000,
                        videoFrame(0),
                        blockGroup(3, 20_000, "Second\r\nline", duration = 5_000),
                        videoFrame(400),
                        blockGroup(3, 12_345, "Earlier", duration = 3_000),
                        audioFrame(500),
                    ),
                    timecodeScale = 100_000,
                ),
            )
        val out = destination

        val result = MatroskaSubtitles.extract(file, 3, out)

        assertEquals(SubtitleExtraction.Extracted(out, SubtitleFormat.SRT), result)
        assertEquals(
            """
            1
            00:00:01,500 --> 00:00:02,700
            Hello there.

            2
            01:00:01,234 --> 01:00:01,534
            Earlier

            3
            01:00:02,000 --> 01:00:02,500
            Second
            line


            """.trimIndent(),
            out.readText(),
        )
        assertEquals(
            listOf("extracted.sub"),
            tmp.root
                .resolve("out")
                .list()!!
                .toList(),
        )
    }

    @Test
    fun assIsRebuiltInReadOrderWithAnEventsSectionAdded() {
        val header =
            "[Script Info]\r\nScriptType: v4.00+\r\n\r\n" +
                "[V4+ Styles]\r\nFormat: Name, Fontname\r\nStyle: Default,Arial\r\n"
        val file =
            write(
                mkv(
                    listOf(video, trackEntry(3, TYPE_SUBTITLE, "S_TEXT/ASS", codecPrivate = header.toByteArray())),
                    cluster(
                        0,
                        blockGroup(
                            3,
                            1_000,
                            "1,0,Default,,0,0,0,,Later in the file, first on screen",
                            duration = 1_500,
                        ),
                        videoFrame(1_500),
                        blockGroup(3, 2_000, "0,0,Default,Bob,0,0,0,,Hello, world", duration = 500),
                    ),
                ),
            )
        val out = destination

        val result = MatroskaSubtitles.extract(file, 3, out)

        assertEquals(SubtitleExtraction.Extracted(out, SubtitleFormat.ASS), result)
        assertEquals(
            """
            [Script Info]
            ScriptType: v4.00+

            [V4+ Styles]
            Format: Name, Fontname
            Style: Default,Arial

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:02.00,0:00:02.50,Default,Bob,0,0,0,,Hello, world
            Dialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,Later in the file, first on screen

            """.trimIndent(),
            out.readText(),
        )
    }

    @Test
    fun ssaWithAnEventsSectionButNoFormatLineGetsOne() {
        val header = "[Script Info]\nScriptType: v4.00\n\n[Events]\n"
        val file =
            write(
                mkv(
                    listOf(trackEntry(7, TYPE_SUBTITLE, "S_TEXT/SSA", codecPrivate = header.toByteArray())),
                    cluster(0, blockGroup(7, 250, "0,Marked=0,Default,,0000,0000,0000,,Hi", duration = 1_000)),
                ),
            )
        val out = destination

        assertEquals(SubtitleExtraction.Extracted(out, SubtitleFormat.ASS), MatroskaSubtitles.extract(file, 7, out))
        assertEquals(
            "[Script Info]\nScriptType: v4.00\n\n[Events]\n" +
                "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
                "Dialogue: Marked=0,0:00:00.25,0:00:01.25,Default,,0000,0000,0000,,Hi\n",
            out.readText(),
        )
    }

    @Test
    fun aBlockWithoutDurationEndsAtTheNextCueOrFiveSecondsLater() {
        val file =
            write(
                mkv(
                    listOf(video, srtTrack),
                    cluster(0, simpleBlock(3, 1_000, "One"), videoFrame(2_000), simpleBlock(3, 3_000, "Two")),
                    cluster(6_000, simpleBlock(3, 0, "Three")),
                ),
            )
        val out = destination

        MatroskaSubtitles.extract(file, 3, out)

        assertEquals(
            "1\n00:00:01,000 --> 00:00:03,000\nOne\n\n" +
                "2\n00:00:03,000 --> 00:00:06,000\nTwo\n\n" +
                "3\n00:00:06,000 --> 00:00:11,000\nThree\n\n",
            out.readText(),
        )
    }

    @Test
    fun zlibCompressedFramesAreInflated() {
        val file =
            write(
                mkv(
                    listOf(trackEntry(3, TYPE_SUBTITLE, "S_TEXT/UTF8", encodings = compression(0))),
                    cluster(0, blockGroup(3, 500, deflate("Compressed line"), duration = 1_000)),
                ),
            )
        val out = destination

        assertEquals(SubtitleExtraction.Extracted(out, SubtitleFormat.SRT), MatroskaSubtitles.extract(file, 3, out))
        assertEquals("1\n00:00:00,500 --> 00:00:01,500\nCompressed line\n\n", out.readText())
    }

    @Test
    fun headerStrippedFramesGetTheStrippedBytesBack() {
        val file =
            write(
                mkv(
                    listOf(
                        trackEntry(3, TYPE_SUBTITLE, "S_TEXT/UTF8", encodings = compression(3, "Hel".toByteArray())),
                    ),
                    cluster(0, blockGroup(3, 0, "lo there", duration = 2_000)),
                ),
            )
        val out = destination

        assertEquals(SubtitleExtraction.Extracted(out, SubtitleFormat.SRT), MatroskaSubtitles.extract(file, 3, out))
        assertEquals("1\n00:00:00,000 --> 00:00:02,000\nHello there\n\n", out.readText())
    }

    @Test
    fun otherCompressionsAndEncryptionFail() {
        val bzip =
            write(mkv(listOf(trackEntry(3, TYPE_SUBTITLE, "S_TEXT/UTF8", encodings = compression(1))), cluster(0)))
        val encrypted =
            write(
                mkv(
                    listOf(trackEntry(3, TYPE_SUBTITLE, "S_TEXT/UTF8", encodings = EbmlWriter.encryption())),
                    cluster(0),
                ),
            )

        assertEquals(
            SubtitleExtraction.Failed("unsupported content compression"),
            MatroskaSubtitles.extract(bzip, 3, destination),
        )
        assertEquals(
            SubtitleExtraction.Failed("encrypted subtitle track"),
            MatroskaSubtitles.extract(encrypted, 3, destination),
        )
        assertFalse(destination.exists())
    }

    @Test
    fun aPgsTrackIsNotTextBased() {
        val file =
            write(
                mkv(
                    listOf(video, trackEntry(4, TYPE_SUBTITLE, "S_HDMV/PGS")),
                    cluster(0, simpleBlock(4, 0, ByteArray(300) { 1 })),
                ),
            )

        assertEquals(SubtitleExtraction.NotTextBased, MatroskaSubtitles.extract(file, 4, destination))
        assertFalse(destination.exists())
    }

    @Test
    fun webVttBecomesSrtKeepingTheTextAndDroppingSettingsAndStyling() {
        // CodecPrivate carries the WEBVTT header and a STYLE block; neither reaches the SRT.
        val header = "WEBVTT\n\nSTYLE\n::cue { color: yellow }\n".toByteArray()
        val file =
            write(
                mkv(
                    listOf(
                        video,
                        trackEntry(3, TYPE_SUBTITLE, "S_TEXT/WEBVTT", language = "eng", codecPrivate = header),
                    ),
                    cluster(
                        0,
                        blockGroup(3, 1_250, "<v Bob>Hello, <c.yellow>world</c></v>!", duration = 1_000),
                        videoFrame(2_000),
                        blockGroup(
                            3,
                            3_000,
                            "<i.loud>Tom &amp; Jerry</i>\n<b>&lt;3</b> <00:00:03.500>later",
                            duration = 2_000,
                        ),
                        simpleBlock(3, 6_000, "No duration"),
                    ),
                ),
            )
        val out = destination

        assertEquals(SubtitleExtraction.Extracted(out, SubtitleFormat.SRT), MatroskaSubtitles.extract(file, 3, out))
        assertEquals(
            """
            1
            00:00:01,250 --> 00:00:02,250
            Hello, world!

            2
            00:00:03,000 --> 00:00:05,000
            <i>Tom & Jerry</i>
            <b><3</b> later

            3
            00:00:06,000 --> 00:00:11,000
            No duration


            """.trimIndent(),
            out.readText(),
        )
    }

    @Test
    fun anotherTextCodecIsUnsupported() {
        val file = write(mkv(listOf(trackEntry(3, TYPE_SUBTITLE, "S_TEXT/USF")), cluster(0, simpleBlock(3, 0, "Hi"))))

        assertEquals(
            SubtitleExtraction.Failed("unsupported subtitle codec S_TEXT/USF"),
            MatroskaSubtitles.extract(file, 3, destination),
        )
        assertFalse(destination.exists())
    }

    @Test
    fun anUnknownOrNonSubtitleTrackIsNotFound() {
        val file = write(mkv(listOf(video, srtTrack), cluster(0, simpleBlock(3, 0, "Hi"))))

        assertEquals(SubtitleExtraction.TrackNotFound, MatroskaSubtitles.extract(file, 9, destination))
        assertEquals(SubtitleExtraction.TrackNotFound, MatroskaSubtitles.extract(file, 1, destination))
    }

    @Test
    fun aTrackWithoutBlocksHasNoCues() {
        val file = write(mkv(listOf(video, srtTrack), cluster(0, videoFrame(0))))

        assertEquals(SubtitleExtraction.Failed("no cues"), MatroskaSubtitles.extract(file, 3, destination))
        assertFalse(destination.exists())
    }

    @Test
    fun aLacedSubtitleBlockFails() {
        val file = write(mkv(listOf(srtTrack), cluster(0, simpleBlock(3, 0, "Hi".toByteArray(), flags = 0x82))))

        assertEquals(SubtitleExtraction.Failed("laced subtitle block"), MatroskaSubtitles.extract(file, 3, destination))
    }

    @Test
    fun anMp4IsNotMatroskaAndLeavesNoDestination() {
        val result = MatroskaSubtitles.extract(write(mp4Header()), 3, destination)

        assertEquals(SubtitleExtraction.Failed("not a Matroska file"), result)
        assertFalse(destination.exists())
    }

    @Test
    fun aFileTruncatedMidClusterFailsAndLeavesNoDestination() {
        val whole =
            mkv(
                listOf(video, srtTrack),
                cluster(0, simpleBlock(3, 0, "One"), videoFrame(100)),
                cluster(5_000, videoFrame(0), simpleBlock(3, 10, "Two"), videoFrame(200)),
            )
        // Cut in the middle of the second Cluster's first video frame.
        val file = write(whole.copyOf(whole.size - 4_500))

        assertEquals(SubtitleExtraction.Failed("truncated file"), MatroskaSubtitles.extract(file, 3, destination))
        assertFalse(destination.exists())
    }

    @Test
    fun unknownSizeSegmentAndClustersEndWhereTheNextElementStarts() {
        val file =
            write(
                EbmlWriter.concat(
                    EbmlWriter.ebmlHeader(),
                    EbmlWriter.unknownSize(
                        EbmlWriter.SEGMENT,
                        EbmlWriter.element(EbmlWriter.TRACKS, video, srtTrack),
                        EbmlWriter.unknownSize(
                            EbmlWriter.CLUSTER,
                            EbmlWriter.uint(EbmlWriter.TIMECODE, 1_000),
                            videoFrame(0),
                            simpleBlock(3, 0, "A"),
                        ),
                        EbmlWriter.unknownSize(
                            EbmlWriter.CLUSTER,
                            EbmlWriter.uint(EbmlWriter.TIMECODE, 4_000),
                            simpleBlock(3, 0, "B"),
                        ),
                        EbmlWriter.element(EbmlWriter.CUES, ByteArray(16)),
                    ),
                ),
            )
        val out = destination

        assertEquals(SubtitleExtraction.Extracted(out, SubtitleFormat.SRT), MatroskaSubtitles.extract(file, 3, out))
        assertEquals(
            "1\n00:00:01,000 --> 00:00:04,000\nA\n\n2\n00:00:04,000 --> 00:00:09,000\nB\n\n",
            out.readText(),
        )
    }

    @Test
    fun anExistingDestinationIsReplacedAndAMissingDirectoryFails() {
        val file = write(mkv(listOf(srtTrack), cluster(0, simpleBlock(3, 0, "New"))))
        val out = destination.apply { writeText("old") }

        assertEquals(SubtitleExtraction.Extracted(out, SubtitleFormat.SRT), MatroskaSubtitles.extract(file, 3, out))
        assertEquals("1\n00:00:00,000 --> 00:00:05,000\nNew\n\n", out.readText())

        val nowhere = tmp.root.resolve("missing/dir/out.srt")
        assertEquals(SubtitleExtraction.Failed("I/O error"), MatroskaSubtitles.extract(file, 3, nowhere))
        assertFalse(nowhere.exists())
    }

    @Test
    fun aMissingMediaFileFailsWithoutNamingItsPath() {
        val result = MatroskaSubtitles.extract(tmp.root.resolve("secret/movie.mkv"), 3, destination)

        assertEquals(SubtitleExtraction.Failed("I/O error"), result)
        assertEquals(
            MkvTracksResult.Failed("I/O error"),
            MatroskaSubtitles.textTracks(tmp.root.resolve("secret/movie.mkv")),
        )
    }

    @Test
    fun codecPrivateIsReturnedAsStored() {
        val bytes = byteArrayOf(1, 2, 3)
        val file = write(mkv(listOf(trackEntry(3, TYPE_SUBTITLE, "S_TEXT/ASS", codecPrivate = bytes))))

        val tracks = (MatroskaSubtitles.textTracks(file) as MkvTracksResult.Tracks).tracks

        assertArrayEquals(bytes, tracks.single().codecPrivate)
    }

    private fun mp4Header(): ByteArray =
        byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray() + ByteArray(20) + byteArrayOf(0, 0, 0, 8) +
            "mdat".toByteArray()

    private fun deflate(text: String): ByteArray {
        val deflater = Deflater()
        deflater.setInput(text.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = ByteArray(256)
        val size = deflater.deflate(out)
        deflater.end()
        return out.copyOf(size)
    }
}
