package com.teachermovies.player.api

import java.io.File

/**
 * Outcome of [Player.extractTextSubtitle]. Every failure is one of these members -- the call itself
 * never throws -- so a caller outside `:player` can branch on it without knowing about libVLC or
 * Matroska.
 */
sealed interface SubtitleExtraction {
    /** The track was written to [file] (the `destination` the caller passed) as [format]. */
    data class Extracted(
        val file: File,
        val format: SubtitleFormat,
    ) : SubtitleExtraction

    /** No media is open, or the open media has no embedded subtitle track with that id. */
    data object TrackNotFound : SubtitleExtraction

    /** The track is image-based (PGS, VobSub, DVB): it carries no text to extract. */
    data object NotTextBased : SubtitleExtraction

    /**
     * Anything else: an unsupported container or codec, a truncated or corrupt file, an I/O error
     * or a timeout. [reason] is a short English message that never names a path other than the
     * `destination`.
     */
    data class Failed(
        val reason: String,
    ) : SubtitleExtraction
}
