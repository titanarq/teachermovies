package com.teachermovies.player.mkv

import com.teachermovies.player.api.SubtitleExtraction
import com.teachermovies.player.api.SubtitleFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Pure-Kotlin Matroska/WebM reader for the one job libVLC 3 cannot do for this product: writing an
 * embedded text subtitle track out as a timed `.srt`/`.ass` (control-plane decision on #115).
 *
 * It walks the file element by element (RFC 9559) and reads only headers, the `Info`/`Tracks`
 * metadata and the chosen track's blocks: every other Block/SimpleBlock payload -- all the video
 * and audio -- is jumped over with a seek, never read. Every failure comes back as a sealed result;
 * nothing here throws.
 */
internal object MatroskaSubtitles {
    /** The subtitle tracks of [file], or why they could not be read. */
    fun textTracks(file: File): MkvTracksResult =
        try {
            file.openChannel().use { channel ->
                val source = EbmlSource(channel)
                val segmentEnd = openSegment(source) ?: return MkvTracksResult.NotMatroska
                val tracks =
                    walkUntilTracks(source, segmentEnd)
                        ?: return MkvTracksResult.Failed("no Tracks element")
                MkvTracksResult.Tracks(tracks.filter { it.type == TRACK_TYPE_SUBTITLE }.map { it.toTextTrack() })
            }
        } catch (e: ExtractionFailure) {
            MkvTracksResult.Failed(e.reason)
        } catch (e: IOException) {
            MkvTracksResult.Failed(e.toReason())
        } catch (e: RuntimeException) {
            MkvTracksResult.Failed("corrupt file")
        }

    /**
     * Writes the subtitle track [trackNumber] of [file] to [destination]: SRT for `S_TEXT/UTF8` and
     * `S_TEXT/WEBVTT` (cue text only, through [WebVttText]), ASS
     * for `S_TEXT/ASS`/`S_TEXT/SSA`. [destination] only appears once every cue is collected.
     */
    fun extract(
        file: File,
        trackNumber: Long,
        destination: File,
    ): SubtitleExtraction =
        try {
            val collected =
                file.openChannel().use { channel ->
                    val source = EbmlSource(channel)
                    val segmentEnd =
                        openSegment(source) ?: return SubtitleExtraction.Failed("not a Matroska file")
                    when (val walk = walkClusters(source, segmentEnd, trackNumber)) {
                        is Walk.Done -> walk
                        is Walk.Stop -> return walk.result
                    }
                }
            val timed =
                SubtitleWriter.timedCues(
                    collected.blocks.map { decodeBlock(it, collected.track) },
                    collected.scale,
                )
            val cues =
                if (collected.track.codecId == CODEC_WEBVTT) {
                    timed.map { SubtitleWriter.TimedCue(it.startMs, it.endMs, WebVttText.toSrt(it.text)) }
                } else {
                    timed
                }
            val format = formatOf(collected.track.codecId)
            val text =
                when (format) {
                    SubtitleFormat.SRT -> SubtitleWriter.srt(cues)
                    SubtitleFormat.ASS -> SubtitleWriter.ass(assHeader(collected.track), cues)
                } ?: return SubtitleExtraction.Failed("no cues")
            SubtitleWriter.writeAtomically(destination, text)
            SubtitleExtraction.Extracted(destination, format)
        } catch (e: ExtractionFailure) {
            SubtitleExtraction.Failed(e.reason)
        } catch (e: IOException) {
            SubtitleExtraction.Failed(e.toReason())
        } catch (e: RuntimeException) {
            SubtitleExtraction.Failed("corrupt file")
        }

    // region Walking the file

    /**
     * Checks the EBML header and positions [source] at the first child of the `Segment`, returning
     * where the Segment ends (the file length for an unknown-size one); null when it is not
     * Matroska/WebM.
     */
    private fun openSegment(source: EbmlSource): Long? {
        if (source.length < EBML_MAGIC.size) return null
        val magic = source.readBytes(EBML_MAGIC.size.toLong())
        if (!magic.contentEquals(EBML_MAGIC)) return null
        source.seek(0)
        val ebml = source.readHeader() ?: return null
        if (ebml.isUnknownSize) throw CorruptException("unknown-size EBML header")
        requireInFile(source, ebml)
        var docType = DEFAULT_DOC_TYPE
        children(source, ebml) { child ->
            if (child.id == ID_DOC_TYPE) docType = source.readString(child)
        }
        if (docType !in DOC_TYPES) return null
        while (true) {
            val header = source.readHeader() ?: throw ExtractionFailure("no Segment element")
            if (header.id == ID_SEGMENT) {
                // A Segment longer than the file is left for the Cluster walk to report: the
                // Tracks of a file still downloading are usually all there.
                return if (header.isUnknownSize) source.length else header.end
            }
            skip(source, header)
        }
    }

    /** Reads top-level elements up to and including `Tracks`; null when the Segment has none. */
    private fun walkUntilTracks(
        source: EbmlSource,
        segmentEnd: Long,
    ): List<TrackInfo>? {
        while (source.position < segmentEnd) {
            val header = source.readHeader() ?: return null
            if (header.id == ID_TRACKS) return readTracks(source, header)
            skip(source, header)
        }
        return null
    }

    private sealed interface Walk {
        class Done(
            val track: TrackInfo,
            val scale: Long,
            val blocks: List<RawBlock>,
        ) : Walk

        class Stop(
            val result: SubtitleExtraction,
        ) : Walk
    }

    /**
     * One flat pass over the Segment. A `Cluster` is entered rather than skipped, so its `Timecode`,
     * `SimpleBlock` and `BlockGroup` children come up in the same loop -- which is also what makes an
     * unknown-size Cluster work: it simply ends where the next Cluster (or other top-level element)
     * starts.
     */
    private fun walkClusters(
        source: EbmlSource,
        segmentEnd: Long,
        trackNumber: Long,
    ): Walk {
        var scale = DEFAULT_TIMECODE_SCALE
        var track: TrackInfo? = null
        var inCluster = false
        var clusterTimecode: Long? = null
        val blocks = mutableListOf<RawBlock>()
        while (source.position < segmentEnd) {
            val header = source.readHeader() ?: break
            when (header.id) {
                ID_CLUSTER -> {
                    if (!header.isUnknownSize) requireInFile(source, header)
                    inCluster = true
                    clusterTimecode = null
                }

                ID_TIMECODE -> {
                    clusterTimecode = source.readUint(header)
                }

                ID_SIMPLE_BLOCK -> {
                    val block = readBlockIfTrack(source, header, trackNumber)
                    if (block != null) {
                        blocks += RawBlock(blockTime(clusterTimecode, inCluster, block), null, block.data)
                    }
                }

                ID_BLOCK_GROUP -> {
                    val group = readBlockGroup(source, header, trackNumber)
                    if (group != null) {
                        blocks +=
                            RawBlock(blockTime(clusterTimecode, inCluster, group.first), group.second, group.first.data)
                    }
                }

                ID_INFO -> {
                    scale = readTimecodeScale(source, header)
                }

                ID_TRACKS -> {
                    val chosen =
                        readTracks(source, header).firstOrNull {
                            it.number == trackNumber &&
                                it.type == TRACK_TYPE_SUBTITLE
                        }
                            ?: return Walk.Stop(SubtitleExtraction.TrackNotFound)
                    classify(chosen)?.let { return Walk.Stop(it) }
                    track = chosen
                }

                else -> {
                    skip(source, header)
                }
            }
        }
        if (segmentEnd > source.length) throw TruncatedException()
        val found = track ?: return Walk.Stop(SubtitleExtraction.Failed("no Tracks element"))
        return Walk.Done(found, scale, blocks)
    }

    private fun blockTime(
        clusterTimecode: Long?,
        inCluster: Boolean,
        block: BlockFrame,
    ): Long {
        if (!inCluster || clusterTimecode == null) throw CorruptException("block outside a timed Cluster")
        return clusterTimecode + block.relativeTimecode
    }

    private class BlockFrame(
        val relativeTimecode: Int,
        val data: ByteArray,
    )

    /**
     * The frame of a `Block`/`SimpleBlock` when it belongs to [trackNumber]; otherwise null, with
     * [source] moved past the payload without reading it (only its first few header bytes are).
     */
    private fun readBlockIfTrack(
        source: EbmlSource,
        header: EbmlHeader,
        trackNumber: Long,
    ): BlockFrame? {
        if (header.isUnknownSize) throw CorruptException("unknown-size block")
        requireInFile(source, header)
        val head = source.readBytes(minOf(header.size, BLOCK_HEAD_MAX))
        val (number, numberLength) = EbmlSource.vintAt(head, 0)
        if (number != trackNumber) {
            source.seek(header.end)
            return null
        }
        if (head.size < numberLength + BLOCK_TIMECODE_AND_FLAGS) throw CorruptException("block too short")
        val relative =
            ((head[numberLength].toInt() shl Byte.SIZE_BITS) or (head[numberLength + 1].toInt() and 0xFF))
                .toShort()
                .toInt()
        val flags = head[numberLength + 2].toInt() and 0xFF
        if ((flags and LACING_MASK) != 0) throw ExtractionFailure("laced subtitle block")
        val frameStart = header.dataStart + numberLength + BLOCK_TIMECODE_AND_FLAGS
        source.seek(frameStart)
        val data = source.readBytes(header.end - frameStart)
        return BlockFrame(relative, data)
    }

    /** A `BlockGroup`'s `Block` for [trackNumber] and its `BlockDuration`, or null for another track. */
    private fun readBlockGroup(
        source: EbmlSource,
        header: EbmlHeader,
        trackNumber: Long,
    ): Pair<BlockFrame, Long?>? {
        if (header.isUnknownSize) throw CorruptException("unknown-size BlockGroup")
        requireInFile(source, header)
        var frame: BlockFrame? = null
        var otherTrack = false
        var duration: Long? = null
        children(source, header) { child ->
            when (child.id) {
                ID_BLOCK -> {
                    frame = readBlockIfTrack(source, child, trackNumber)
                    otherTrack = frame == null
                }

                ID_BLOCK_DURATION -> {
                    duration = source.readUint(child)
                }
            }
        }
        if (otherTrack) return null
        val found = frame ?: return null
        return found to duration
    }

    private fun readTimecodeScale(
        source: EbmlSource,
        header: EbmlHeader,
    ): Long {
        if (header.isUnknownSize) throw CorruptException("unknown-size Info")
        requireInFile(source, header)
        var scale = DEFAULT_TIMECODE_SCALE
        children(source, header) { child ->
            if (child.id == ID_TIMECODE_SCALE) scale = source.readUint(child)
        }
        if (scale <= 0) throw CorruptException("invalid TimecodeScale")
        return scale
    }

    private fun readTracks(
        source: EbmlSource,
        header: EbmlHeader,
    ): List<TrackInfo> {
        if (header.isUnknownSize) throw CorruptException("unknown-size Tracks")
        requireInFile(source, header)
        val tracks = mutableListOf<TrackInfo>()
        children(source, header) { child ->
            if (child.id == ID_TRACK_ENTRY) tracks += readTrackEntry(source, child)
        }
        return tracks
    }

    private fun readTrackEntry(
        source: EbmlSource,
        header: EbmlHeader,
    ): TrackInfo {
        var number = 0L
        var type = 0L
        var codecId = ""
        var language = DEFAULT_LANGUAGE
        var languageIetf: String? = null
        var name: String? = null
        var codecPrivate: ByteArray? = null
        var encodings = emptyList<ContentEncoding>()
        children(source, header) { child ->
            when (child.id) {
                ID_TRACK_NUMBER -> number = source.readUint(child)
                ID_TRACK_TYPE -> type = source.readUint(child)
                ID_CODEC_ID -> codecId = source.readString(child)
                ID_LANGUAGE -> language = source.readString(child)
                ID_LANGUAGE_IETF -> languageIetf = source.readString(child)
                ID_NAME -> name = source.readString(child)
                ID_CODEC_PRIVATE -> codecPrivate = source.readBytes(child.size)
                ID_CONTENT_ENCODINGS -> encodings = readContentEncodings(source, child)
            }
        }
        return TrackInfo(number, type, codecId, language, languageIetf, name, codecPrivate, encodings)
    }

    private fun readContentEncodings(
        source: EbmlSource,
        header: EbmlHeader,
    ): List<ContentEncoding> {
        val encodings = mutableListOf<ContentEncoding>()
        children(source, header) { encoding ->
            if (encoding.id != ID_CONTENT_ENCODING) return@children
            var order = 0L
            var scope = SCOPE_FRAMES
            var type = ENCODING_TYPE_COMPRESSION
            var algorithm = COMP_ALGO_ZLIB
            var settings = ByteArray(0)
            var encrypted = false
            children(source, encoding) { child ->
                when (child.id) {
                    ID_CONTENT_ENCODING_ORDER -> {
                        order = source.readUint(child)
                    }

                    ID_CONTENT_ENCODING_SCOPE -> {
                        scope = source.readUint(child)
                    }

                    ID_CONTENT_ENCODING_TYPE -> {
                        type = source.readUint(child)
                    }

                    ID_CONTENT_ENCRYPTION -> {
                        encrypted = true
                    }

                    ID_CONTENT_COMPRESSION -> {
                        children(source, child) { compression ->
                            when (compression.id) {
                                ID_CONTENT_COMP_ALGO -> algorithm = source.readUint(compression)
                                ID_CONTENT_COMP_SETTINGS -> settings = source.readBytes(compression.size)
                            }
                        }
                    }
                }
            }
            encodings +=
                ContentEncoding(order, scope, encrypted || type != ENCODING_TYPE_COMPRESSION, algorithm, settings)
        }
        return encodings
    }

    /** Calls [onChild] for each child of the known-size master [parent], then leaves [source] at its end. */
    private inline fun children(
        source: EbmlSource,
        parent: EbmlHeader,
        onChild: (EbmlHeader) -> Unit,
    ) {
        if (parent.isUnknownSize) throw CorruptException("unknown-size element")
        while (source.position < parent.end) {
            val child = source.readHeader() ?: throw TruncatedException()
            if (child.isUnknownSize || child.end > parent.end) throw CorruptException("element overruns its parent")
            onChild(child)
            source.seek(child.end)
        }
        source.seek(parent.end)
    }

    private fun skip(
        source: EbmlSource,
        header: EbmlHeader,
    ) {
        if (header.isUnknownSize) throw CorruptException("unknown-size element")
        requireInFile(source, header)
        source.seek(header.end)
    }

    private fun requireInFile(
        source: EbmlSource,
        header: EbmlHeader,
    ) {
        if (header.end > source.length) throw TruncatedException()
    }

    private fun EbmlSource.readUint(header: EbmlHeader): Long = EbmlSource.uint(readBytes(header.size))

    private fun EbmlSource.readString(header: EbmlHeader): String =
        String(readBytes(header.size), Charsets.UTF_8).trimEnd('\u0000')

    // endregion

    // region Codecs and content encodings

    /** Null when the chosen track can be extracted; otherwise the result that says why not. */
    private fun classify(track: TrackInfo): SubtitleExtraction? {
        val codec = track.codecId
        return when {
            codec == CODEC_UTF8 || codec == CODEC_WEBVTT || codec == CODEC_ASS || codec == CODEC_SSA -> {
                when {
                    track.encodings.any { it.encrypted } -> {
                        SubtitleExtraction.Failed("encrypted subtitle track")
                    }

                    track.encodings.any {
                        it.algorithm != COMP_ALGO_ZLIB && it.algorithm != COMP_ALGO_HEADER_STRIPPING
                    } -> {
                        SubtitleExtraction.Failed("unsupported content compression")
                    }

                    else -> {
                        null
                    }
                }
            }

            codec in IMAGE_CODECS || codec.startsWith(IMAGE_CODEC_PREFIX) -> {
                SubtitleExtraction.NotTextBased
            }

            else -> {
                SubtitleExtraction.Failed("unsupported subtitle codec $codec")
            }
        }
    }

    private fun formatOf(codecId: String): SubtitleFormat =
        if (codecId == CODEC_UTF8 || codecId == CODEC_WEBVTT) SubtitleFormat.SRT else SubtitleFormat.ASS

    private fun decodeBlock(
        block: RawBlock,
        track: TrackInfo,
    ): SubtitleWriter.RawCue =
        SubtitleWriter.RawCue(block.ticks, block.durationTicks, decode(block.data, track.encodings, SCOPE_FRAMES))

    private fun assHeader(track: TrackInfo): String {
        val raw = track.codecPrivate ?: return ""
        return String(decode(raw, track.encodings, SCOPE_CODEC_PRIVATE), Charsets.UTF_8)
    }

    /** Undoes [encodings] that cover [scope], highest `ContentEncodingOrder` first (RFC 9559). */
    private fun decode(
        data: ByteArray,
        encodings: List<ContentEncoding>,
        scope: Long,
    ): ByteArray =
        encodings
            .filter { (it.scope and scope) != 0L }
            .sortedByDescending { it.order }
            .fold(data) { bytes, encoding ->
                when (encoding.algorithm) {
                    COMP_ALGO_ZLIB -> inflate(bytes)
                    COMP_ALGO_HEADER_STRIPPING -> encoding.settings + bytes
                    else -> throw ExtractionFailure("unsupported content compression")
                }
            }

    private fun inflate(bytes: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(bytes.size * 2)
            val chunk = ByteArray(INFLATE_CHUNK)
            while (!inflater.finished()) {
                val count = inflater.inflate(chunk)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw CorruptException("corrupt compressed block")
                }
                out.write(chunk, 0, count)
                if (out.size() > EbmlSource.MAX_READ) throw CorruptException("compressed block too large")
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw CorruptException("corrupt compressed block")
        } finally {
            inflater.end()
        }
    }

    // endregion

    private fun File.openChannel(): FileChannel = FileChannel.open(toPath(), StandardOpenOption.READ)

    private fun IOException.toReason(): String =
        when (this) {
            is TruncatedException -> "truncated file"

            is CorruptException -> "corrupt file: $message"

            is ClosedByInterruptException -> "interrupted"

            // Never the exception's own message: it names the media's path.
            else -> "I/O error"
        }

    /** A reason to stop that is already the final [SubtitleExtraction.Failed.reason]. */
    private class ExtractionFailure(
        val reason: String,
    ) : RuntimeException(reason)

    private class RawBlock(
        val ticks: Long,
        val durationTicks: Long?,
        val data: ByteArray,
    )

    private class ContentEncoding(
        val order: Long,
        val scope: Long,
        val encrypted: Boolean,
        val algorithm: Long,
        val settings: ByteArray,
    )

    private class TrackInfo(
        val number: Long,
        val type: Long,
        val codecId: String,
        val language: String,
        val languageIetf: String?,
        val name: String?,
        val codecPrivate: ByteArray?,
        val encodings: List<ContentEncoding>,
    ) {
        fun toTextTrack() = MkvTextTrack(number, codecId, language, languageIetf, name, codecPrivate)
    }

    private val EBML_MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
    private const val DEFAULT_DOC_TYPE = "matroska"
    private val DOC_TYPES = setOf("matroska", "webm")
    private const val DEFAULT_TIMECODE_SCALE = 1_000_000L
    private const val DEFAULT_LANGUAGE = "eng"
    private const val TRACK_TYPE_SUBTITLE = 0x11L

    /** Track number vint (at most 8 bytes), 16-bit relative timecode and the flags byte. */
    private const val BLOCK_HEAD_MAX = 11L
    private const val BLOCK_TIMECODE_AND_FLAGS = 3
    private const val LACING_MASK = 0x06
    private const val INFLATE_CHUNK = 8 * 1024

    private const val SCOPE_FRAMES = 1L
    private const val SCOPE_CODEC_PRIVATE = 2L
    private const val ENCODING_TYPE_COMPRESSION = 0L
    private const val COMP_ALGO_ZLIB = 0L
    private const val COMP_ALGO_HEADER_STRIPPING = 3L

    private const val CODEC_UTF8 = "S_TEXT/UTF8"
    private const val CODEC_WEBVTT = "S_TEXT/WEBVTT"
    private const val CODEC_ASS = "S_TEXT/ASS"
    private const val CODEC_SSA = "S_TEXT/SSA"
    private val IMAGE_CODECS = setOf("S_HDMV/PGS", "S_VOBSUB", "S_DVBSUB")
    private const val IMAGE_CODEC_PREFIX = "S_IMAGE/"

    private const val ID_DOC_TYPE = 0x4282L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMECODE_SCALE = 0x2AD7B1L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_CODEC_ID = 0x86L
    private const val ID_LANGUAGE = 0x22B59CL
    private const val ID_LANGUAGE_IETF = 0x22B59DL
    private const val ID_NAME = 0x536EL
    private const val ID_CODEC_PRIVATE = 0x63A2L
    private const val ID_CONTENT_ENCODINGS = 0x6D80L
    private const val ID_CONTENT_ENCODING = 0x6240L
    private const val ID_CONTENT_ENCODING_ORDER = 0x5031L
    private const val ID_CONTENT_ENCODING_SCOPE = 0x5032L
    private const val ID_CONTENT_ENCODING_TYPE = 0x5033L
    private const val ID_CONTENT_COMPRESSION = 0x5034L
    private const val ID_CONTENT_COMP_ALGO = 0x4254L
    private const val ID_CONTENT_COMP_SETTINGS = 0x4255L
    private const val ID_CONTENT_ENCRYPTION = 0x5035L
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_TIMECODE = 0xE7L
    private const val ID_SIMPLE_BLOCK = 0xA3L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L
    private const val ID_BLOCK_DURATION = 0x9BL
}
