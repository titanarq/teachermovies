package com.teachermovies.player.mp4

import java.io.ByteArrayOutputStream

/**
 * Just enough of an ISO-BMFF muxer to build tiny MP4 files in memory, so the tests carry no binary
 * fixtures. Each track's samples go into one `mdat`, one chunk per sample, laid out either before
 * or after `moov`.
 */
internal object Mp4Writer {
    /** One sample: its bytes and its `stts` duration in the track's timescale. */
    class Sample(
        val bytes: ByteArray,
        val delta: Long,
    )

    class TrackSpec(
        val trackId: Long,
        val handler: String,
        val sampleEntry: String,
        val timescale: Long = 1_000,
        val samples: List<Sample> = emptyList(),
        val language: String = "eng",
        val co64: Boolean = false,
        val compactSizes: Boolean = false,
        val mdhdVersion1: Boolean = false,
        /** Samples per chunk; a chunk holds consecutive samples. */
        val samplesPerChunk: Int = 1,
    )

    /** A tx3g sample: 16-bit length and UTF-8 text, plus an unrelated modifier box after it. */
    fun tx3g(
        text: String,
        delta: Long,
        withStyleBox: Boolean = false,
    ): Sample {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val style = if (withStyleBox) box("styl", u16(0)) else ByteArray(0)
        return Sample(concat(u16(bytes.size), bytes, style), delta)
    }

    /** An empty tx3g sample: how a gap between cues is stored. */
    fun gap(delta: Long): Sample = Sample(u16(0), delta)

    /**
     * A whole file: `ftyp`, then `mdat` and `moov` in the order [moovFirst] asks for.
     * [extraMoovBoxes] are appended inside `moov` (an `mvex`, say).
     */
    fun mp4(
        tracks: List<TrackSpec>,
        moovFirst: Boolean = false,
        extraMoovBoxes: List<ByteArray> = emptyList(),
        /**
         * When set, no `mdat` is written: the chunk offsets start at this file offset and the caller
         * writes [payload] there itself (past 4 GiB in a sparse file, for `co64`).
         */
        sampleBase: Long? = null,
        /** Top-level boxes written right after `ftyp` (a `free` box with a 64-bit size, say). */
        afterFtyp: List<ByteArray> = emptyList(),
    ): ByteArray {
        val ftyp = concat(box("ftyp", "isom".toByteArray(), u32(0x200), "isomiso2mp41".toByteArray()), *afterFtyp.toTypedArray())
        val mdat = box("mdat", payload(tracks))
        fun moovAt(mdatPayloadStart: Long): ByteArray {
            var offset = mdatPayloadStart
            val traks =
                tracks.map { track ->
                    val starts = track.samples.map { s -> offset.also { offset += s.bytes.size } }
                    trak(track, starts)
                }
            return box("moov", box("mvhd", ByteArray(100)), *traks.toTypedArray(), *extraMoovBoxes.toTypedArray())
        }
        if (sampleBase != null) return concat(ftyp, moovAt(sampleBase))
        return if (moovFirst) {
            val size = moovAt(0).size
            concat(ftyp, moovAt((ftyp.size + size + 8).toLong()), mdat)
        } else {
            concat(ftyp, mdat, moovAt((ftyp.size + 8).toLong()))
        }
    }

    /** Every sample of [tracks], in the order [mp4] lays them out. */
    fun payload(tracks: List<TrackSpec>): ByteArray = concat(*tracks.flatMap { t -> t.samples.map { it.bytes } }.toTypedArray())

    private fun trak(
        track: TrackSpec,
        sampleStarts: List<Long>,
    ): ByteArray {
        val tkhd = fullBox("tkhd", 0, 0, u32(0), u32(0), u32(track.trackId), ByteArray(68))
        val mdhd =
            if (track.mdhdVersion1) {
                fullBox("mdhd", 1, 0, ByteArray(16), u32(track.timescale), ByteArray(8), u16(packLanguage(track.language)), u16(0))
            } else {
                fullBox("mdhd", 0, 0, ByteArray(8), u32(track.timescale), u32(0), u16(packLanguage(track.language)), u16(0))
            }
        val hdlr = fullBox("hdlr", 0, 0, u32(0), track.handler.toByteArray(), ByteArray(12), byteArrayOf(0))
        val entry = box(track.sampleEntry, ByteArray(6), u16(1), ByteArray(30))
        val stsd = fullBox("stsd", 0, 0, u32(1), entry)
        val stts = fullBox("stts", 0, 0, u32(track.samples.size.toLong()), *track.samples.map { concat(u32(1), u32(it.delta)) }.toTypedArray())
        val sizes = track.samples.map { it.bytes.size }
        val stsz =
            if (track.compactSizes) {
                fullBox("stz2", 0, 0, byteArrayOf(0, 0, 0, 16), u32(sizes.size.toLong()), *sizes.map { u16(it) }.toTypedArray())
            } else {
                fullBox("stsz", 0, 0, u32(0), u32(sizes.size.toLong()), *sizes.map { u32(it.toLong()) }.toTypedArray())
            }
        val chunkStarts = sampleStarts.filterIndexed { i, _ -> i % track.samplesPerChunk == 0 }
        val stsc = fullBox("stsc", 0, 0, u32(1), u32(1), u32(track.samplesPerChunk.toLong()), u32(1))
        val chunks =
            if (track.co64) {
                fullBox("co64", 0, 0, u32(chunkStarts.size.toLong()), *chunkStarts.map { u64(it) }.toTypedArray())
            } else {
                fullBox("stco", 0, 0, u32(chunkStarts.size.toLong()), *chunkStarts.map { u32(it) }.toTypedArray())
            }
        val stbl = box("stbl", stsd, stts, stsc, stsz, chunks)
        return box("trak", tkhd, box("mdia", mdhd, hdlr, box("minf", stbl)))
    }

    fun box(
        type: String,
        vararg payload: ByteArray,
    ): ByteArray {
        val body = concat(*payload)
        return concat(u32(8L + body.size), type.toByteArray(Charsets.US_ASCII), body)
    }

    /** A box with a 64-bit `largesize` header. */
    fun largeBox(
        type: String,
        vararg payload: ByteArray,
    ): ByteArray {
        val body = concat(*payload)
        return concat(u32(1), type.toByteArray(Charsets.US_ASCII), u64(16L + body.size), body)
    }

    private fun fullBox(
        type: String,
        version: Int,
        flags: Int,
        vararg payload: ByteArray,
    ): ByteArray = box(type, byteArrayOf(version.toByte(), (flags shr 16).toByte(), (flags shr 8).toByte(), flags.toByte()), *payload)

    private fun packLanguage(code: String): Int = code.fold(0) { acc, c -> (acc shl 5) or (c.code - 0x60) }

    fun u16(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())

    fun u32(value: Long): ByteArray = ByteArray(4) { (value shr (24 - 8 * it)).toByte() }

    fun u64(value: Long): ByteArray = ByteArray(8) { (value shr (56 - 8 * it)).toByte() }

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach(out::write)
        return out.toByteArray()
    }
}
