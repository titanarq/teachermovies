package com.teachermovies.player.mp4

/**
 * One subtitle `trak` of an ISO-BMFF (MP4/M4V/MOV) file, as [Mp4Subtitles.textTracks] reads it:
 * every track whose `hdlr` is a subtitle handler (`text`, `sbtl`, `subt`, `subp`, `clcp`), whatever
 * its codec -- the [sampleEntry] is what tells a `tx3g` track from a VobSub one, and the track
 * mapping needs them all to count libVLC's subtitle tracks.
 */
internal data class Mp4TextTrack(
    /** `tkhd` `track_ID`. */
    val trackId: Long,
    /** `hdlr` `handler_type`, e.g. `sbtl`. */
    val handler: String,
    /** The type of the first `stsd` entry, e.g. `tx3g`; empty when the track has none. */
    val sampleEntry: String,
    /** `mdhd` language (ISO 639-2/T), `und` when unset. */
    val language: String,
)

/** What [Mp4Subtitles.textTracks] found. */
internal sealed interface Mp4TracksResult {
    /** The subtitle tracks, in `moov` order; empty when the file has none. */
    data class Tracks(
        val tracks: List<Mp4TextTrack>,
    ) : Mp4TracksResult

    /** The file does not start with an `ftyp` box. */
    data object NotMp4 : Mp4TracksResult

    /** An ISO-BMFF file that could not be read: truncated, corrupt, no `moov`, or an I/O error. */
    data class Failed(
        val reason: String,
    ) : Mp4TracksResult
}
