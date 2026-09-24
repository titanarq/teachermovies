package com.teachermovies.player.fake

import com.teachermovies.player.api.Player
import com.teachermovies.player.api.PlayerState
import com.teachermovies.player.api.Track
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [Player] for every test above `:player` (ADR-0003: fakes live in the main source set).
 *
 * It never touches libVLC or the filesystem -- [open] and [addExternalSubtitle] only read a file's
 * name -- so it runs as a plain JVM test double. A test drives what the real implementation would
 * learn from the media itself through [emitTracks], [emitPosition], [emitDuration], [end] and
 * [fail], and reads the result straight off the `StateFlow`s: every control updates them
 * synchronously, before it returns.
 */
class FakePlayer : Player {
    private val mutableState = MutableStateFlow<PlayerState>(PlayerState.Idle)
    private val mutablePositionMs = MutableStateFlow(0L)
    private val mutableDurationMs = MutableStateFlow(0L)
    private val mutableAudioTracks = MutableStateFlow<List<Track>>(emptyList())
    private val mutableSubtitleTracks = MutableStateFlow<List<Track>>(emptyList())
    private val mutableSelectedAudioId = MutableStateFlow<String?>(null)
    private val mutableSelectedSubtitleId = MutableStateFlow<String?>(null)

    override val state: StateFlow<PlayerState> = mutableState.asStateFlow()
    override val positionMs: StateFlow<Long> = mutablePositionMs.asStateFlow()
    override val durationMs: StateFlow<Long> = mutableDurationMs.asStateFlow()
    override val audioTracks: StateFlow<List<Track>> = mutableAudioTracks.asStateFlow()
    override val subtitleTracks: StateFlow<List<Track>> = mutableSubtitleTracks.asStateFlow()
    override val selectedAudioId: StateFlow<String?> = mutableSelectedAudioId.asStateFlow()
    override val selectedSubtitleId: StateFlow<String?> = mutableSelectedSubtitleId.asStateFlow()

    override fun open(
        file: File,
        startPositionMs: Long,
    ) {
        mutableState.value = PlayerState.Opening
        mutablePositionMs.value = startPositionMs
    }

    override fun play() {
        mutableState.value = PlayerState.Playing
    }

    override fun pause() {
        mutableState.value = PlayerState.Paused
    }

    override fun togglePlayPause() {
        when (mutableState.value) {
            PlayerState.Playing -> pause()
            PlayerState.Paused -> play()
            // Nothing to toggle: no media is loaded, it is not ready, or playback is over.
            PlayerState.Idle,
            PlayerState.Opening,
            PlayerState.Ended,
            is PlayerState.Error,
            -> Unit
        }
    }

    override fun seekTo(ms: Long) {
        mutablePositionMs.value = ms.coerceIn(0L, mutableDurationMs.value)
    }

    override fun seekBy(deltaMs: Long) {
        seekTo(mutablePositionMs.value + deltaMs)
    }

    override fun selectAudio(id: String) {
        mutableSelectedAudioId.value = id
    }

    override fun selectSubtitle(id: String?) {
        mutableSelectedSubtitleId.value = id
    }

    override fun addExternalSubtitle(
        file: File,
        select: Boolean,
    ) {
        val track = Track(id = "ext:${file.name}", name = file.name, language = null)
        mutableSubtitleTracks.value = mutableSubtitleTracks.value + track
        if (select) {
            selectSubtitle(track.id)
        }
    }

    /**
     * Back to [PlayerState.Idle], leaving the emitted position, duration and tracks alone so a
     * test can still assert on what the media was after the screen released the player.
     */
    override fun release() {
        mutableState.value = PlayerState.Idle
    }

    /** Replaces both track lists, as the real player does once it has parsed a media. */
    fun emitTracks(
        audio: List<Track>,
        subs: List<Track>,
    ) {
        mutableAudioTracks.value = audio
        mutableSubtitleTracks.value = subs
    }

    /** Reports a new playback position, as the real player's position updates do. */
    fun emitPosition(ms: Long) {
        mutablePositionMs.value = ms
    }

    /** Reports the media's length, which the real player only knows once it has parsed it. */
    fun emitDuration(ms: Long) {
        mutableDurationMs.value = ms
    }

    /** Simulates running out of media: [PlayerState.Ended], at the end of the known duration. */
    fun end() {
        mutablePositionMs.value = mutableDurationMs.value
        mutableState.value = PlayerState.Ended
    }

    /** Simulates a playback failure, as a missing codec or an unreadable file would. */
    fun fail(message: String) {
        mutableState.value = PlayerState.Error(message)
    }
}
