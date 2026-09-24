package com.teachermovies.player.vlc

import com.teachermovies.player.api.SubtitleExtraction
import com.teachermovies.player.api.Track
import com.teachermovies.player.mkv.MatroskaSubtitles
import com.teachermovies.player.mkv.MkvTrackMapping
import com.teachermovies.player.mkv.MkvTracksResult
import com.teachermovies.player.mp4.Mp4Subtitles
import com.teachermovies.player.mp4.Mp4TracksResult
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The pure half of [VlcPlayer.extractTextSubtitle]: sniffs the container of the opened file and
 * hands the libVLC subtitle [Track] id to the reader that understands it -- [MatroskaSubtitles]
 * for an EBML header, [Mp4Subtitles] for a leading `ftyp` box. No `org.videolan` type, so it runs
 * in JVM tests; blocking I/O, so the caller picks the dispatcher and the timeout.
 */
internal object EmbeddedSubtitles {
    enum class Container { MATROSKA, MP4, OTHER }

    fun extract(
        file: File,
        trackId: String,
        vlcSubtitleTracks: List<Track>,
        destination: File,
    ): SubtitleExtraction {
        if (vlcSubtitleTracks.none { it.id == trackId }) return SubtitleExtraction.TrackNotFound
        return when (containerOf(file)) {
            Container.MATROSKA -> extractMatroska(file, trackId, vlcSubtitleTracks, destination)
            Container.MP4 -> extractMp4(file, trackId, vlcSubtitleTracks, destination)
            Container.OTHER -> SubtitleExtraction.Failed(NOT_SUPPORTED)
        }
    }

    /** From the first bytes alone: the EBML magic, or `ftyp` as the type of the first box. */
    fun containerOf(file: File): Container {
        val head =
            try {
                RandomAccessFile(file, "r").use { raf ->
                    val bytes = ByteArray(SNIFF_BYTES)
                    val read = raf.read(bytes)
                    if (read <= 0) ByteArray(0) else bytes.copyOf(read)
                }
            } catch (e: IOException) {
                return Container.OTHER
            }
        return when {
            head.size >= EBML_MAGIC.size && head.copyOf(EBML_MAGIC.size).contentEquals(EBML_MAGIC) -> Container.MATROSKA
            head.size >= SNIFF_BYTES && String(head, 4, 4, Charsets.US_ASCII) == FTYP -> Container.MP4
            else -> Container.OTHER
        }
    }

    private fun extractMatroska(
        file: File,
        trackId: String,
        vlcSubtitleTracks: List<Track>,
        destination: File,
    ): SubtitleExtraction =
        when (val tracks = MatroskaSubtitles.textTracks(file)) {
            // EBML, but a DocType other than matroska/webm.
            MkvTracksResult.NotMatroska -> SubtitleExtraction.Failed(NOT_SUPPORTED)
            is MkvTracksResult.Failed -> SubtitleExtraction.Failed(tracks.reason)
            is MkvTracksResult.Tracks ->
                when (val number = MkvTrackMapping.resolve(trackId, vlcSubtitleTracks, tracks.tracks)) {
                    null -> SubtitleExtraction.TrackNotFound
                    else -> MatroskaSubtitles.extract(file, number, destination)
                }
        }

    private fun extractMp4(
        file: File,
        trackId: String,
        vlcSubtitleTracks: List<Track>,
        destination: File,
    ): SubtitleExtraction =
        when (val tracks = Mp4Subtitles.textTracks(file)) {
            Mp4TracksResult.NotMp4 -> SubtitleExtraction.Failed(NOT_SUPPORTED)
            is Mp4TracksResult.Failed -> SubtitleExtraction.Failed(tracks.reason)
            is Mp4TracksResult.Tracks ->
                when (val id = MkvTrackMapping.resolveNumber(trackId, vlcSubtitleTracks, tracks.tracks.map { it.trackId })) {
                    null -> SubtitleExtraction.TrackNotFound
                    else -> Mp4Subtitles.extract(file, id, destination)
                }
        }

    const val NOT_SUPPORTED = "container not supported"
    private const val SNIFF_BYTES = 8
    private const val FTYP = "ftyp"
    private val EBML_MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
}
