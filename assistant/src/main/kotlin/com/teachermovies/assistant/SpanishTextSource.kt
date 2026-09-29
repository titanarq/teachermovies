package com.teachermovies.assistant

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
