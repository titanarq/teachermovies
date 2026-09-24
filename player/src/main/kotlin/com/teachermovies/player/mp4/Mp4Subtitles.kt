package com.teachermovies.player.mp4

import com.teachermovies.player.api.SubtitleExtraction
import com.teachermovies.player.api.SubtitleFormat
import com.teachermovies.player.mkv.CorruptException
import com.teachermovies.player.mkv.SubtitleWriter
import com.teachermovies.player.mkv.TruncatedException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Pure-Kotlin ISO-BMFF (MP4/M4V/MOV) reader for the one job libVLC 3 cannot do for this product:
 * writing an embedded `tx3g` (3GPP timed text, "mov_text") track out as a timed `.srt` (#154).
 *
 * It walks the top-level boxes seeking past everything but `moov` -- `mdat`, the media itself, is
 * never read as a whole -- loads `moov` into memory, and from the chosen track's sample tables
 * (`stts`, `stsz`/`stz2`, `stsc`, `stco`/`co64`) reads just that track's samples. Times come from
 * the track's own `mdhd` timescale; edit lists are not applied. Every failure comes back as a sealed
 * result; nothing here throws.
 */
internal object Mp4Subtitles {
    /** The subtitle tracks of [file], or why they could not be read. */
    fun textTracks(file: File): Mp4TracksResult =
        try {
            file.openChannel().use { channel ->
                val moov = readMoov(channel) ?: return Mp4TracksResult.NotMp4
                Mp4TracksResult.Tracks(
                    parseTracks(moov).filter { it.handler in SUBTITLE_HANDLERS }.map { it.toTextTrack() },
                )
            }
        } catch (e: Mp4Failure) {
            Mp4TracksResult.Failed(e.reason)
        } catch (e: IOException) {
            Mp4TracksResult.Failed(e.toReason())
        } catch (e: RuntimeException) {
            Mp4TracksResult.Failed("corrupt file")
        }

    /**
     * Writes the `tx3g` track [trackId] (`tkhd` `track_ID`) of [file] to [destination] as SRT.
     * [destination] only appears once every sample is read.
     */
    fun extract(
        file: File,
        trackId: Long,
        destination: File,
    ): SubtitleExtraction =
        try {
            val cues =
                file.openChannel().use { channel ->
                    val moov = readMoov(channel) ?: return SubtitleExtraction.Failed("not an MP4 file")
                    val track =
                        parseTracks(moov).firstOrNull { it.trackId == trackId && it.handler in SUBTITLE_HANDLERS }
                            ?: return SubtitleExtraction.TrackNotFound
                    classify(track)?.let { return it }
                    if (moov.children(0, moov.size).any {
                            it.type == MVEX
                        }
                    ) {
                        return SubtitleExtraction.Failed("fragmented MP4 not supported")
                    }
                    readCues(channel, moov, track)
                }
            val text = SubtitleWriter.srt(cues) ?: return SubtitleExtraction.Failed("no cues")
            SubtitleWriter.writeAtomically(destination, text)
            SubtitleExtraction.Extracted(destination, SubtitleFormat.SRT)
        } catch (e: Mp4Failure) {
            SubtitleExtraction.Failed(e.reason)
        } catch (e: IOException) {
            SubtitleExtraction.Failed(e.toReason())
        } catch (e: RuntimeException) {
            SubtitleExtraction.Failed("corrupt file")
        }

    // region Top-level boxes

    /**
     * The payload of the `moov` box, or null when the file does not start with `ftyp`. A `moov`
     * (or a box before it) that runs past the end of the file is a truncated file.
     */
    private fun readMoov(channel: FileChannel): Bytes? {
        val length = channel.size()
        var position = 0L
        var first = true
        while (position < length) {
            val header =
                readHeaderAt(channel, position, length) ?: if (first) return null else throw TruncatedException()
            if (first && header.type != FTYP) return null
            first = false
            if (header.end > length) throw TruncatedException()
            if (header.type == MOOV) {
                val size = header.end - header.dataStart
                if (size > MAX_MOOV_BYTES) throw Mp4Failure("moov too large")
                return Bytes(readAt(channel, header.dataStart, size.toInt()))
            }
            position = header.end
        }
        if (first) return null
        throw Mp4Failure("no moov box")
    }

    private class FileBox(
        val type: String,
        val dataStart: Long,
        val end: Long,
    )

    private fun readHeaderAt(
        channel: FileChannel,
        position: Long,
        length: Long,
    ): FileBox? {
        if (length - position < BOX_HEADER) return null
        val head = readAt(channel, position, BOX_HEADER)
        val size32 = Bytes(head).u32(0)
        val type = Bytes(head).type(4)
        return when (size32) {
            0L -> {
                FileBox(type, position + BOX_HEADER, length)
            }

            1L -> {
                if (length - position < LARGE_BOX_HEADER) return null
                val large = Bytes(readAt(channel, position + BOX_HEADER, 8)).u64(0)
                if (large < LARGE_BOX_HEADER) throw CorruptException("bad box size")
                FileBox(type, position + LARGE_BOX_HEADER, position + large)
            }

            else -> {
                if (size32 < BOX_HEADER) throw CorruptException("bad box size")
                FileBox(type, position + BOX_HEADER, position + size32)
            }
        }
    }

    // endregion

    // region moov

    private class TrackInfo(
        val trackId: Long,
        val handler: String,
        val timescale: Long,
        val language: String,
        val sampleEntry: String,
        /** Payload range of `stbl` inside the `moov` bytes, or null. */
        val stbl: Box?,
    ) {
        fun toTextTrack() = Mp4TextTrack(trackId, handler, sampleEntry, language)
    }

    private fun parseTracks(moov: Bytes): List<TrackInfo> =
        moov.children(0, moov.size).filter { it.type == TRAK }.map { trak ->
            val tkhd = moov.child(trak, TKHD) ?: throw CorruptException("trak without tkhd")
            val trackId = moov.u32(tkhd.start + if (moov.u8(tkhd.start) == 1) 20 else 12)
            val mdia = moov.child(trak, MDIA)
            val mdhd = mdia?.let { moov.child(it, MDHD) }
            var timescale = 0L
            var language = UNDETERMINED
            if (mdhd != null) {
                val v1 = moov.u8(mdhd.start) == 1
                timescale = moov.u32(mdhd.start + if (v1) 20 else 12)
                language = languageOf(moov.u16(mdhd.start + if (v1) 32 else 20))
            }
            val handler = mdia?.let { moov.child(it, HDLR) }?.let { moov.type(it.start + 8) }.orEmpty()
            val stbl = mdia?.let { moov.child(it, MINF) }?.let { moov.child(it, STBL) }
            val stsd = stbl?.let { moov.child(it, STSD) }
            val sampleEntry =
                stsd
                    ?.takeIf { moov.u32(it.start + 4) > 0 && it.end - it.start >= 16 }
                    ?.let { moov.type(it.start + 12) }
                    .orEmpty()
            TrackInfo(trackId, handler, timescale, language, sampleEntry, stbl)
        }

    /** `mdhd` packs ISO 639-2/T as three 5-bit letters; QuickTime's small values are Mac codes. */
    private fun languageOf(packed: Int): String {
        // Mac language code 0 is English; every other Mac code (and 0x7FFF, "unspecified") is not
        // worth a table here.
        if (packed == 0) return ENGLISH
        if (packed < MAC_LANGUAGE_LIMIT || packed == 0x7FFF) return UNDETERMINED
        val letters = List(3) { i -> (((packed shr (10 - 5 * i)) and 0x1F) + 0x60).toChar() }
        if (letters.any { it !in 'a'..'z' }) return UNDETERMINED
        return letters.joinToString("")
    }

    /** Null when the chosen track can be extracted; otherwise the result that says why not. */
    private fun classify(track: TrackInfo): SubtitleExtraction? =
        when {
            track.sampleEntry == TX3G -> {
                if (track.timescale <=
                    0
                ) {
                    SubtitleExtraction.Failed("corrupt file: invalid timescale")
                } else {
                    null
                }
            }

            track.handler == HANDLER_SUBPICTURE -> {
                SubtitleExtraction.NotTextBased
            }

            else -> {
                SubtitleExtraction.Failed("unsupported subtitle codec ${track.sampleEntry.ifEmpty { track.handler }}")
            }
        }

    // endregion

    // region Samples

    private fun readCues(
        channel: FileChannel,
        moov: Bytes,
        track: TrackInfo,
    ): List<SubtitleWriter.TimedCue> {
        val stbl = track.stbl ?: throw CorruptException("track without stbl")
        val sizes = sampleSizes(moov, stbl)
        val deltas = sampleDeltas(moov, stbl, sizes.size)
        val offsets = sampleOffsets(moov, stbl, sizes)
        val length = channel.size()
        val cues = mutableListOf<SubtitleWriter.TimedCue>()
        var ticks = 0L
        for (i in sizes.indices) {
            val start = ticks
            ticks += deltas[i]
            val size = sizes[i]
            // A sample with no text is how tx3g encodes a gap between two cues.
            if (size < TEXT_LENGTH_BYTES) continue
            if (size > MAX_SAMPLE_BYTES) throw CorruptException("sample too large")
            if (offsets[i] < 0 || offsets[i] + size > length) throw TruncatedException()
            val sample = Bytes(readAt(channel, offsets[i], size.toInt()))
            val textLength = sample.u16(0)
            if (textLength == 0) continue
            if (TEXT_LENGTH_BYTES + textLength > size) throw CorruptException("text longer than its sample")
            val text = sample.text(TEXT_LENGTH_BYTES.toInt(), textLength)
            cues += SubtitleWriter.TimedCue(toMs(start, track.timescale), toMs(ticks, track.timescale), text)
        }
        return cues
    }

    private fun sampleSizes(
        moov: Bytes,
        stbl: Box,
    ): LongArray {
        moov.child(stbl, STSZ)?.let { stsz ->
            val constant = moov.u32(stsz.start + 4)
            val count = checkedCount(moov.u32(stsz.start + 8))
            if (constant != 0L) return LongArray(count) { constant }
            requireTable(stsz, 12, count, 4)
            return LongArray(count) { moov.u32(stsz.start + 12 + 4 * it) }
        }
        val stz2 = moov.child(stbl, STZ2) ?: throw CorruptException("no sample size table")
        val fieldSize = moov.u8(stz2.start + 7)
        val count = checkedCount(moov.u32(stz2.start + 8))
        val base = stz2.start + 12
        return when (fieldSize) {
            4 -> {
                requireTable(stz2, 12, (count + 1) / 2, 1)
                LongArray(count) { i ->
                    val byte = moov.u8(base + i / 2)
                    (if (i % 2 == 0) byte shr 4 else byte and 0x0F).toLong()
                }
            }

            8 -> {
                requireTable(stz2, 12, count, 1)
                LongArray(count) { moov.u8(base + it).toLong() }
            }

            16 -> {
                requireTable(stz2, 12, count, 2)
                LongArray(count) { moov.u16(base + 2 * it).toLong() }
            }

            else -> {
                throw CorruptException("bad stz2 field size")
            }
        }
    }

    /** Per-sample durations from the run-length `stts`. */
    private fun sampleDeltas(
        moov: Bytes,
        stbl: Box,
        sampleCount: Int,
    ): LongArray {
        val stts = moov.child(stbl, STTS) ?: throw CorruptException("no stts")
        val entries = checkedCount(moov.u32(stts.start + 4))
        requireTable(stts, 8, entries, 8)
        val deltas = LongArray(sampleCount)
        var next = 0
        for (e in 0 until entries) {
            val count = moov.u32(stts.start + 8 + 8 * e)
            val delta = moov.u32(stts.start + 12 + 8 * e)
            var n = 0L
            while (n < count && next < sampleCount) {
                deltas[next++] = delta
                n++
            }
        }
        if (next < sampleCount) throw CorruptException("stts shorter than the samples")
        return deltas
    }

    /** Per-sample file offsets from `stsc` (samples per chunk) and `stco`/`co64` (chunk offsets). */
    private fun sampleOffsets(
        moov: Bytes,
        stbl: Box,
        sizes: LongArray,
    ): LongArray {
        val chunkOffsets =
            moov.child(stbl, STCO)?.let { stco ->
                val count = checkedCount(moov.u32(stco.start + 4))
                requireTable(stco, 8, count, 4)
                LongArray(count) { moov.u32(stco.start + 8 + 4 * it) }
            } ?: moov.child(stbl, CO64)?.let { co64 ->
                val count = checkedCount(moov.u32(co64.start + 4))
                requireTable(co64, 8, count, 8)
                LongArray(count) { moov.u64(co64.start + 8 + 8 * it) }
            } ?: throw CorruptException("no chunk offset table")
        val stsc = moov.child(stbl, STSC) ?: throw CorruptException("no stsc")
        val runs = checkedCount(moov.u32(stsc.start + 4))
        requireTable(stsc, 8, runs, 12)
        val offsets = LongArray(sizes.size)
        var sample = 0
        for (r in 0 until runs) {
            val firstChunk = moov.u32(stsc.start + 8 + 12 * r)
            val perChunk = moov.u32(stsc.start + 12 + 12 * r)
            val lastChunk =
                if (r + 1 <
                    runs
                ) {
                    moov.u32(stsc.start + 8 + 12 * (r + 1)) - 1
                } else {
                    chunkOffsets.size.toLong()
                }
            if (firstChunk < 1 || lastChunk > chunkOffsets.size) throw CorruptException("bad stsc")
            for (chunk in firstChunk..lastChunk) {
                var offset = chunkOffsets[(chunk - 1).toInt()]
                var n = 0L
                while (n < perChunk && sample < sizes.size) {
                    offsets[sample] = offset
                    offset += sizes[sample]
                    sample++
                    n++
                }
            }
        }
        if (sample < sizes.size) throw CorruptException("chunks hold fewer samples than stsz")
        return offsets
    }

    private fun checkedCount(count: Long): Int {
        if (count > MAX_TABLE_ENTRIES) throw CorruptException("table too large")
        return count.toInt()
    }

    private fun requireTable(
        box: Box,
        headerBytes: Int,
        entries: Int,
        entryBytes: Int,
    ) {
        if (box.start + headerBytes + entries.toLong() * entryBytes >
            box.end
        ) {
            throw CorruptException("table overruns its box")
        }
    }

    private fun toMs(
        ticks: Long,
        timescale: Long,
    ): Long = ticks * MS_PER_SECOND / timescale

    // endregion

    // region Byte access

    /** A box inside the `moov` bytes: [start] is its payload's first byte, [end] one past its last. */
    private class Box(
        val type: String,
        val start: Int,
        val end: Int,
    )

    /** Big-endian reads over an in-memory box tree, bounds-checked. */
    private class Bytes(
        val data: ByteArray,
    ) {
        val size: Int get() = data.size

        fun u8(at: Int): Int = byte(at)

        fun u16(at: Int): Int = (byte(at) shl 8) or byte(at + 1)

        fun u32(at: Int): Long = (u16(at).toLong() shl 16) or u16(at + 2).toLong()

        fun u64(at: Int): Long {
            val value = (u32(at) shl 32) or u32(at + 4)
            if (value < 0) throw CorruptException("64-bit value out of range")
            return value
        }

        fun type(at: Int): String = String(CharArray(4) { byte(at + it).toChar() })

        /** tx3g text: UTF-16 when it starts with a byte-order mark, UTF-8 otherwise. */
        fun text(
            at: Int,
            length: Int,
        ): String {
            if (at + length > data.size) throw CorruptException("text overruns its sample")
            val bom = length >= 2 && byte(at) == 0xFE && byte(at + 1) == 0xFF
            return if (bom) {
                String(
                    data,
                    at + 2,
                    length - 2,
                    Charsets.UTF_16BE,
                )
            } else {
                String(data, at, length, Charsets.UTF_8)
            }
        }

        /** The boxes directly inside the payload range [start, end). */
        fun children(
            start: Int,
            end: Int,
        ): List<Box> {
            val boxes = mutableListOf<Box>()
            var position = start
            while (position + BOX_HEADER <= end) {
                val size32 = u32(position)
                val type = type(position + 4)
                val (dataStart, boxEnd) =
                    when (size32) {
                        0L -> position + BOX_HEADER to end.toLong()
                        1L -> position + LARGE_BOX_HEADER to position + u64(position + BOX_HEADER)
                        else -> position + BOX_HEADER to position + size32
                    }
                if (boxEnd > end || boxEnd < dataStart) throw CorruptException("box overruns its parent")
                boxes += Box(type, dataStart, boxEnd.toInt())
                position = boxEnd.toInt()
            }
            return boxes
        }

        fun child(
            parent: Box,
            type: String,
        ): Box? = children(parent.start, parent.end).firstOrNull { it.type == type }

        private fun byte(at: Int): Int {
            if (at < 0 || at >= data.size) throw CorruptException("box overruns its parent")
            return data[at].toInt() and 0xFF
        }
    }

    private fun readAt(
        channel: FileChannel,
        position: Long,
        size: Int,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(size)
        var at = position
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, at)
            if (read < 0) throw TruncatedException()
            at += read
        }
        return buffer.array()
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
    private class Mp4Failure(
        val reason: String,
    ) : RuntimeException(reason)

    private const val BOX_HEADER = 8
    private const val LARGE_BOX_HEADER = 16
    private const val TEXT_LENGTH_BYTES = 2L
    private const val MS_PER_SECOND = 1_000L
    private const val MAX_MOOV_BYTES = 64L * 1024 * 1024
    private const val MAX_SAMPLE_BYTES = 1L * 1024 * 1024
    private const val MAX_TABLE_ENTRIES = 16_000_000L
    private const val MAC_LANGUAGE_LIMIT = 0x400
    private const val ENGLISH = "eng"
    private const val UNDETERMINED = "und"

    private const val FTYP = "ftyp"
    private const val MOOV = "moov"
    private const val MVEX = "mvex"
    private const val TRAK = "trak"
    private const val TKHD = "tkhd"
    private const val MDIA = "mdia"
    private const val MDHD = "mdhd"
    private const val HDLR = "hdlr"
    private const val MINF = "minf"
    private const val STBL = "stbl"
    private const val STSD = "stsd"
    private const val STTS = "stts"
    private const val STSZ = "stsz"
    private const val STZ2 = "stz2"
    private const val STSC = "stsc"
    private const val STCO = "stco"
    private const val CO64 = "co64"
    private const val TX3G = "tx3g"
    private const val HANDLER_SUBPICTURE = "subp"

    /** `hdlr` types whose tracks libVLC lists as subtitles. */
    private val SUBTITLE_HANDLERS = setOf("text", "sbtl", "subt", "subp", "clcp")
}
