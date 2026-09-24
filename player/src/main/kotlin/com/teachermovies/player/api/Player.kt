package com.teachermovies.player.api

import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * The one contract every other module programs against for playback.
 *
 * No libVLC type crosses this boundary (ADR-0001 §2, `docs/modules/player.md`): `app-tv` and
 * `assistant` depend only on this interface and the value types in this package, and their tests
 * use `FakePlayer` from `com.teachermovies.player.fake` instead of the real libVLC-backed
 * implementation.
 *
 * Every `StateFlow` here emits its current value on collection and then on every change, so a
 * collector never has to poll. The controls are plain functions rather than `suspend` ones: they
 * are what a D-pad press or a phone on the LAN turns into, and an implementation hands them to
 * whatever thread the media library needs.
 */
interface Player {
    /** Lifecycle of the loaded media, independent of its position. */
    val state: StateFlow<PlayerState>

    /** Playback position in milliseconds; 0 while nothing is open. */
    val positionMs: StateFlow<Long>

    /** Total length in milliseconds; 0 until the media has been parsed. */
    val durationMs: StateFlow<Long>

    /** Audio tracks of the open media, empty until they are known. */
    val audioTracks: StateFlow<List<Track>>

    /** Subtitle tracks of the open media, internal ones and externally added ones alike. */
    val subtitleTracks: StateFlow<List<Track>>

    /** [Track.id] of the active audio track; null while none is selected. */
    val selectedAudioId: StateFlow<String?>

    /** [Track.id] of the active subtitle track; null when subtitles are off. */
    val selectedSubtitleId: StateFlow<String?>

    /**
     * Loads [file] and prepares it for playback, resuming at [startPositionMs] into the media.
     *
     * The position the caller passes is the one `:core-model` persisted for the item, so resuming
     * is a caller decision, not something the player remembers by itself.
     */
    fun open(
        file: File,
        startPositionMs: Long = 0,
    )

    fun play()

    fun pause()

    /** Plays when paused and pauses when playing; does nothing in any other [PlayerState]. */
    fun togglePlayPause()

    /** Seeks to the absolute position [ms], clamped into `[0, durationMs]`. */
    fun seekTo(ms: Long)

    /** Seeks [deltaMs] relative to the current position, clamped into `[0, durationMs]`. */
    fun seekBy(deltaMs: Long)

    /** Makes the audio track [id] the active one. */
    fun selectAudio(id: String)

    /** Makes the subtitle track [id] the active one, or turns subtitles off when it is null. */
    fun selectSubtitle(id: String?)

    /**
     * Adds the subtitle file [file] -- an `.srt`/`.ass` next to the media, or one pushed from the
     * phone -- as one more track of the open media, selecting it right away when [select].
     */
    fun addExternalSubtitle(
        file: File,
        select: Boolean,
    )

    /** Frees the media resources; [open] may be called again afterwards. */
    fun release()
}
