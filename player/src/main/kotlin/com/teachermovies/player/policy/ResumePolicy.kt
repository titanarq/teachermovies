package com.teachermovies.player.policy

/**
 * Where playback should start given the position `:core-model` persisted for an item, so a
 * screen never has to special-case "close enough to the start" or "close enough to the end"
 * itself.
 *
 * Pure Kotlin: it only ever sees plain milliseconds, not a `Player` or a media file.
 */
object ResumePolicy {
    /** Below this, playback starts over rather than resuming a few seconds in. */
    private const val MIN_RESUMABLE_POSITION_MS = 10_000L

    /** Within this of the end, the item is treated as finished rather than resumed. */
    private const val END_OF_MEDIA_MARGIN_MS = 60_000L

    /** Rewind applied to a genuine resume, so playback picks up a little before it left off. */
    private const val REWIND_MS = 3_000L

    /**
     * The position to start playback at: `0` when [lastPositionMs] is under
     * [MIN_RESUMABLE_POSITION_MS], or when [durationMs] is known and [lastPositionMs] is past
     * `durationMs - `[END_OF_MEDIA_MARGIN_MS]; otherwise [lastPositionMs] rewound by [REWIND_MS].
     */
    fun startPosition(
        lastPositionMs: Long,
        durationMs: Long?,
    ): Long {
        val nearTheEnd = durationMs != null && lastPositionMs > durationMs - END_OF_MEDIA_MARGIN_MS
        if (lastPositionMs < MIN_RESUMABLE_POSITION_MS || nearTheEnd) {
            return 0L
        }
        return lastPositionMs - REWIND_MS
    }
}
