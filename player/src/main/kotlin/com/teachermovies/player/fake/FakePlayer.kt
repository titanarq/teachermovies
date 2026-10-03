package com.teachermovies.player.fake

import com.teachermovies.player.api.ExternalSubtitleResult
import com.teachermovies.player.api.Player
import com.teachermovies.player.api.PlayerState
import com.teachermovies.player.api.SubtitleExtraction
import com.teachermovies.player.api.SubtitleFormat
import com.teachermovies.player.api.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * In-memory [Player] for every test above `:player` (ADR-0003: fakes live in the main source set).
 *
 * It never touches libVLC or the filesystem -- [open] and [addExternalSubtitle] only read a file's
 * name -- so it runs as a plain JVM test double. A test drives what the real implementation would
 * learn from the media itself through [emitTracks], [emitPosition], [emitDuration],
 * [deselectTracks], [end] and [fail], and reads the result straight off the `StateFlow`s: every
 * control updates them synchronously, before it returns.
 */
class FakePlayer : Player {
    private val mutableState = MutableStateFlow<PlayerState>(PlayerState.Idle)
    private val mutablePositionMs = MutableStateFlow(0L)
    private val mutableDurationMs = MutableStateFlow(0L)
    private val mutableAudioTracks = MutableStateFlow<List<Track>>(emptyList())
    private val mutableSubtitleTracks = MutableStateFlow<List<Track>>(emptyList())
    private val mutableSelectedAudioId = MutableStateFlow<String?>(null)
    private val mutableSelectedSubtitleId = MutableStateFlow<String?>(null)

    private var extractionOutcome: ExtractionOutcome =
        ExtractionOutcome.Result(SubtitleExtraction.Failed("no extraction configured"))
    private val recordedExtractionCalls = mutableListOf<String>()

    /** Every `trackId` [extractTextSubtitle] was called with, in call order. */
    val extractionCalls: List<String>
        get() = recordedExtractionCalls.toList()

    private val recordedExternalSubtitleCalls = mutableListOf<File>()
    private var externalSubtitleFailure: ExternalSubtitleResult? = null

    /** Every file [addExternalSubtitleTrack] was called with, in call order. */
    val externalSubtitleCalls: List<File>
        get() = recordedExternalSubtitleCalls.toList()

    /** The arguments of the last [open], or null when [open] was never called. */
    var lastOpen: OpenCall? = null
        private set

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
        growing: Boolean,
    ) {
        lastOpen = OpenCall(file, startPositionMs, growing)
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
     * Appends the `ext:` track [addExternalSubtitle] would, unselected, and returns it. Every file is
     * recorded in [externalSubtitleCalls]; [emitExternalSubtitleResult] makes later calls fail instead.
     */
    override suspend fun addExternalSubtitleTrack(file: File): ExternalSubtitleResult {
        recordedExternalSubtitleCalls += file
        externalSubtitleFailure?.let { return it }
        val track = Track(id = "ext:${file.name}", name = file.name, language = null)
        mutableSubtitleTracks.value = mutableSubtitleTracks.value + track
        return ExternalSubtitleResult.Added(track)
    }

    /** Makes later [addExternalSubtitleTrack] calls return [result], or succeed again with null. */
    fun emitExternalSubtitleResult(result: ExternalSubtitleResult?) {
        externalSubtitleFailure = result
    }

    /**
     * [SubtitleExtraction.TrackNotFound] when [trackId] is not one of [subtitleTracks]; otherwise the
     * outcome set by [emitExtractionText] or [emitExtraction] (a `Failed` until one of them is
     * called). Every call is recorded in [extractionCalls].
     */
    override suspend fun extractTextSubtitle(
        trackId: String,
        destination: File,
    ): SubtitleExtraction {
        recordedExtractionCalls += trackId
        if (mutableSubtitleTracks.value.none { it.id == trackId }) {
            return SubtitleExtraction.TrackNotFound
        }
        return when (val outcome = extractionOutcome) {
            is ExtractionOutcome.Result -> {
                outcome.result
            }

            is ExtractionOutcome.Text -> {
                destination.writeText(outcome.text)
                SubtitleExtraction.Extracted(destination, outcome.format)
            }
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

    /**
     * Replaces only the audio track list, leaving the subtitle list as it is: libVLC reports each
     * track as it discovers it, so a media's audio can be known before its subtitle tracks (#248).
     */
    fun emitAudioTracks(audio: List<Track>) {
        mutableAudioTracks.value = audio
    }

    /** Replaces only the subtitle track list; the later half of an [emitAudioTracks] sequence. */
    fun emitSubtitleTracks(subs: List<Track>) {
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

    /**
     * Reports the all-null selection libVLC leaves behind when it deselects every elementary
     * stream, without a state change: the shape its `ESSelected` read-back takes when it arrives
     * before [PlayerState.Ended] does (#261). [end] does it too, together with the state change.
     */
    fun deselectTracks() {
        mutableSelectedAudioId.value = null
        mutableSelectedSubtitleId.value = null
    }

    /**
     * Simulates running out of media: [PlayerState.Ended], at the end of the known duration, with
     * every stream deselected the way libVLC leaves them around the end (#261).
     */
    fun end() {
        mutablePositionMs.value = mutableDurationMs.value
        deselectTracks()
        mutableState.value = PlayerState.Ended
    }

    /** Makes later extractions of a known track write [text] to `destination` and return `Extracted`. */
    fun emitExtractionText(
        text: String,
        format: SubtitleFormat,
    ) {
        extractionOutcome = ExtractionOutcome.Text(text, format)
    }

    /** Makes later extractions of a known track return [result] unchanged. */
    fun emitExtraction(result: SubtitleExtraction) {
        extractionOutcome = ExtractionOutcome.Result(result)
    }

    private sealed interface ExtractionOutcome {
        data class Text(
            val text: String,
            val format: SubtitleFormat,
        ) : ExtractionOutcome

        data class Result(
            val result: SubtitleExtraction,
        ) : ExtractionOutcome
    }

    /** Simulates a playback failure, as a missing codec or an unreadable file would. */
    fun fail(message: String) {
        mutableState.value = PlayerState.Error(message)
    }

    /** What one [open] call was given, recorded in [lastOpen]. */
    data class OpenCall(
        val file: File,
        val startPositionMs: Long,
        val growing: Boolean,
    )
}
