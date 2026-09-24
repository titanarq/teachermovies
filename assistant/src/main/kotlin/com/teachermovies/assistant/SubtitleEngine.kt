package com.teachermovies.assistant

import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.assistant.subtitles.SubtitleIndex
import com.teachermovies.assistant.subtitles.SubtitleTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Keeps [currentSubtitle] synced to [positionMs]: whichever [SubtitleCue] of the loaded
 * [SubtitleTrack] covers the latest reported position, looked up through a [SubtitleIndex].
 *
 * [positionMs] is expected to be a hot flow -- a [Player][com.teachermovies.player.api.Player]'s
 * `positionMs` `StateFlow` in production, a test `MutableStateFlow` in tests -- collected for as
 * long as [scope] stays alive. [StateFlow]'s own conflation means [currentSubtitle] only emits
 * when the cue actually changes: repeated positions inside the same cue, or positions in two
 * different gaps, produce no new emission.
 *
 * @param maxGapMs how long after a cue has ended [cueForCapture] still resolves to it.
 */
class SubtitleEngine(
    private val positionMs: Flow<Long>,
    private val scope: CoroutineScope,
    private val maxGapMs: Long = 3_000,
) {
    private val mutableCurrentSubtitle = MutableStateFlow<SubtitleCue?>(null)
    val currentSubtitle: StateFlow<SubtitleCue?> = mutableCurrentSubtitle.asStateFlow()

    private var index: SubtitleIndex? = null
    private var lastPositionMs: Long = 0L

    init {
        scope.launch {
            positionMs.collect { position ->
                lastPositionMs = position
                mutableCurrentSubtitle.value = index?.cueAt(position)
            }
        }
    }

    /**
     * Loads [track] -- or clears it with `null` -- and re-resolves [currentSubtitle] right away
     * against the last known position, without waiting for the next [positionMs] emission.
     */
    fun load(track: SubtitleTrack?) {
        index = track?.let { SubtitleIndex(it) }
        mutableCurrentSubtitle.value = index?.cueAt(lastPositionMs)
    }

    /**
     * The line a "what did they say?" capture at [positionMs] refers to: the cue covering
     * [positionMs], or the previous cue when it ended no more than [maxGapMs] before it (the
     * viewer reacts after the line is over); `null` otherwise, and while no track is loaded.
     */
    fun cueForCapture(positionMs: Long): SubtitleCue? {
        val cue = index?.cueAtOrBefore(positionMs) ?: return null
        return if (positionMs - cue.endMs <= maxGapMs) cue else null
    }
}
