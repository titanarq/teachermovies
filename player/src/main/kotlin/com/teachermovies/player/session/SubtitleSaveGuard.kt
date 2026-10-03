package com.teachermovies.player.session

/**
 * Keeps a temporary subtitle track out of what [PlaybackSession] persists as the viewer's choice.
 *
 * While [hold] is in force, a save that finds [tempId] selected writes [previousId] instead; any
 * other selection -- a track the viewer picked meanwhile -- is saved as it is. [release] ends it.
 */
interface SubtitleSaveGuard {
    /** [tempId] is shown temporarily; the viewer's own selection (null = none) is [previousId]. */
    fun hold(
        tempId: String,
        previousId: String?,
    )

    /** The temporary track is gone; saves go back to the player's selection. */
    fun release()
}
