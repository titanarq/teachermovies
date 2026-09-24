package com.teachermovies.assistant.subtitles

/**
 * A single timed subtitle cue with plain text (no markup).
 *
 * @property index zero-based position of this cue within its [SubtitleTrack], in [startMs] order.
 * @property startMs cue start time, in milliseconds from the start of the media.
 * @property endMs cue end time, in milliseconds from the start of the media; always `>= startMs`.
 * @property text plain cue text, with any source markup already stripped.
 */
data class SubtitleCue(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * An ordered collection of [SubtitleCue]s parsed from a subtitle file.
 *
 * Cues are sorted by [SubtitleCue.startMs] and renumbered `0..n-1` in that order.
 *
 * @property language optional language tag for the track (not populated by the parsers in this
 *   module; a future task may derive it from the file name or an explicit selection).
 */
data class SubtitleTrack(
    val cues: List<SubtitleCue>,
    val language: String? = null,
)
