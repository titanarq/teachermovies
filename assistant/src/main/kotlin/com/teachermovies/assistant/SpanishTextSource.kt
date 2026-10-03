package com.teachermovies.assistant

import com.teachermovies.assistant.subtitles.SubtitleTrack

/**
 * The Spanish line spoken at a playback position (#343).
 *
 * One function is all `:assistant` needs to draw the Spanish side of a phrase rewind, so the
 * timeline behind it -- the aligned Spanish track -- stays swappable and is owned by its own issue.
 * Tests use `com.teachermovies.assistant.fake.FakeSpanishTextSource` (ADR-0003).
 */
fun interface SpanishTextSource {
    /** The Spanish text spoken at [positionMs], or `null` when the source has none there. */
    fun textAt(positionMs: Long): String?
}

/**
 * The Spanish cues of the movie on its playback (English) timeline, for the phrase rewind to hand
 * the player as a subtitle track (#358). Implemented next to [SpanishTextSource] by the aligned
 * timeline; `null` while there is none.
 */
fun interface SpanishCueSource {
    /** The Spanish cues with the alignment applied inversely, or `null` when none is available. */
    fun playbackCues(): SubtitleTrack?
}
