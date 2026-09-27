package com.teachermovies.core.model

/**
 * How one downloaded Spanish subtitle file lines up with a movie's own timeline (#274, ADR-0005 §6),
 * persisted in `subtitle_alignment` and keyed by ([torrentId], [subtitlePath]). Computed once per
 * file by the aligner (#283), which also decides how good [qualityScore] has to be before the panel
 * shows the aligned line instead of a machine translation.
 *
 * An English timestamp maps to Spanish with
 * `esMs = enMs * [frameRateScale] + [offsetMs]`; [qualityScore] is 0..1, higher is better, and grades
 * the pair of files as a whole rather than one cue. How good is good enough -- 0.6 in the current
 * design -- is the aligner's and the panel's call (#283), not the schema's.
 */
data class SubtitleAlignment(
    val torrentId: TorrentId,
    val subtitlePath: String,
    val offsetMs: Long,
    val frameRateScale: Double,
    val qualityScore: Double,
    val computedAtEpochMs: Long,
) {
    /** The Spanish timestamp a captured English cue at [enMs] lines up with (ADR-0005 §6). */
    fun spanishMsFor(enMs: Long): Long = (enMs * frameRateScale).toLong() + offsetMs
}
