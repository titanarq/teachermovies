package com.teachermovies.player.mkv

/**
 * One subtitle `TrackEntry` (`TrackType` 0x11) of a Matroska file, as [MatroskaSubtitles.textTracks]
 * reads it. Despite the name it is every subtitle track, image-based ones included: the codec is
 * what tells them apart, and [MkvTrackMapping] needs them all to count libVLC's tracks.
 */
internal data class MkvTextTrack(
    val trackNumber: Long,
    val codecId: String,
    /** `Language` (ISO 639-2), `eng` when the element is absent, as Matroska defines. */
    val language: String,
    /** `LanguageIETF` (BCP 47), which takes precedence over [language] when present. */
    val languageIetf: String?,
    val name: String?,
    /** `CodecPrivate` as stored -- still encoded when a `ContentEncoding` covers it. */
    val codecPrivate: ByteArray?,
) {
    // A data class compares arrays by identity; tracks are compared by content in tests.
    override fun equals(other: Any?): Boolean =
        other is MkvTextTrack &&
            trackNumber == other.trackNumber &&
            codecId == other.codecId &&
            language == other.language &&
            languageIetf == other.languageIetf &&
            name == other.name &&
            codecPrivate.contentEqualsNullable(other.codecPrivate)

    override fun hashCode(): Int = trackNumber.hashCode() * 31 + codecId.hashCode()

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
        if (this == null || other == null) this === other else contentEquals(other)
}

/** What [MatroskaSubtitles.textTracks] found. */
internal sealed interface MkvTracksResult {
    /** The subtitle tracks, in `Tracks` order; empty when the file has none. */
    data class Tracks(
        val tracks: List<MkvTextTrack>,
    ) : MkvTracksResult

    /** The file does not start with an EBML header whose DocType is `matroska` or `webm`. */
    data object NotMatroska : MkvTracksResult

    /** A Matroska file that could not be read: truncated, corrupt, no `Tracks`, or an I/O error. */
    data class Failed(
        val reason: String,
    ) : MkvTracksResult
}
