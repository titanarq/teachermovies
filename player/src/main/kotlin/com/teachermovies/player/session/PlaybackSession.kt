package com.teachermovies.player.session

import com.teachermovies.core.model.LibraryItem
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.TorrentRepository
import com.teachermovies.player.api.Player
import com.teachermovies.player.api.PlayerState
import com.teachermovies.player.policy.ResumePolicy
import com.teachermovies.player.policy.TrackPolicy
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Plays one library item on [player]: resumes where the viewer left off, adds the subtitle files
 * sitting next to the movie, re-applies the tracks the viewer chose last time and keeps
 * [TorrentRepository.updatePlayback] current while watching.
 *
 * Progress is written every [SAVE_INTERVAL_MS] of [clock] time while [PlayerState.Playing] (checked
 * on each position update the player reports), when playback pauses, when the viewer changes a
 * track, and on [close]; when playback ends on its own the position is stored as `0` so the next
 * open starts over.
 *
 * [scope] runs the session's collectors (the screen's scope, on one thread) and [clock] returns
 * epoch milliseconds; both are injected so a JVM test drives them deterministically.
 */
class PlaybackSession(
    private val player: Player,
    private val repo: TorrentRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    @Volatile private var current: LibraryItem? = null

    @Volatile private var job: Job? = null

    @Volatile private var tracksApplied = false

    @Volatile private var lastSavedAtMs = 0L

    /**
     * Loads [id] into [player], closing any item this session had open first.
     *
     * Returns [SessionResult.NotFound] when [id] is not a completed library item and
     * [SessionResult.FileMissing] when its media file is not on disk; the player is left untouched
     * in both cases. Otherwise opens the file at [ResumePolicy.startPosition] of the persisted
     * position, adds every subtitle file found by [externalSubtitles] as an unselected track, and
     * starts watching the player: once it reports audio tracks, [TrackPolicy] picks the audio and
     * subtitle tracks from the persisted ids.
     */
    suspend fun open(id: TorrentId): SessionResult {
        close()
        val item = repo.getLibraryItem(id) ?: return SessionResult.NotFound
        val file = File(item.mainFilePath)
        if (!file.isFile) return SessionResult.FileMissing(item.mainFilePath)

        // The library does not store the media's length, so only the "barely started" rule applies
        // here; the end-of-media rule is covered by storing 0 when playback ends.
        player.open(file, ResumePolicy.startPosition(item.lastPositionMs, durationMs = null))
        externalSubtitles(file).forEach { player.addExternalSubtitle(it, select = false) }

        current = item
        tracksApplied = false
        lastSavedAtMs = clock()
        job = scope.launch { watch(item) }
        return SessionResult.Opened(item)
    }

    /**
     * Stops watching the player, saves the current position and tracks (`0` if playback had
     * ended) and releases the player. A no-op when nothing is open; [open] may be called again.
     */
    suspend fun close() {
        val item = current ?: return
        job?.cancelAndJoin()
        job = null
        current = null
        val ended = player.state.value == PlayerState.Ended
        save(item, if (ended) 0L else player.positionMs.value)
        player.release()
    }

    private suspend fun watch(item: LibraryItem) =
        coroutineScope {
            launch { applyTracksWhenKnown(item) }
            launch {
                player.state.collect { state ->
                    when (state) {
                        PlayerState.Paused -> save(item, player.positionMs.value)
                        PlayerState.Ended -> save(item, 0L)
                        PlayerState.Idle,
                        PlayerState.Opening,
                        PlayerState.Playing,
                        is PlayerState.Error,
                        -> Unit
                    }
                }
            }
            launch {
                player.positionMs.collect { positionMs ->
                    val playing = player.state.value == PlayerState.Playing
                    if (playing && clock() - lastSavedAtMs >= SAVE_INTERVAL_MS) {
                        save(item, positionMs)
                    }
                }
            }
        }

    /**
     * Waits for the player to report the media's audio tracks, applies [TrackPolicy] with the
     * persisted ids, then saves whenever the viewer picks another track.
     */
    private suspend fun applyTracksWhenKnown(item: LibraryItem) {
        player.audioTracks.first { it.isNotEmpty() }
        TrackPolicy.audio(player.audioTracks.value, item.audioTrackId)?.let(player::selectAudio)
        player.selectSubtitle(TrackPolicy.subtitle(player.subtitleTracks.value, item.subtitleTrackId))
        tracksApplied = true

        combine(player.selectedAudioId, player.selectedSubtitleId) { audio, sub -> audio to sub }
            .distinctUntilChanged()
            // The first value is the selection just applied, not a change by the viewer.
            .drop(1)
            .collect { save(item, player.positionMs.value) }
    }

    /**
     * Writes [positionMs] and the chosen tracks: the player's selection once [TrackPolicy] has been
     * applied, the persisted ids before that, so a save while still opening never clears them.
     */
    private suspend fun save(
        item: LibraryItem,
        positionMs: Long,
    ) {
        val audio = if (tracksApplied) player.selectedAudioId.value else item.audioTrackId
        val subtitle = if (tracksApplied) player.selectedSubtitleId.value else item.subtitleTrackId
        lastSavedAtMs = clock()
        repo.updatePlayback(item.id, positionMs, audio, subtitle)
    }

    internal companion object {
        /** How often progress is written while playing. */
        const val SAVE_INTERVAL_MS = 5_000L

        private val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")
        private const val SUBS_DIR_NAME = "subs"

        /**
         * Subtitle files (`.srt`/`.ass`/`.ssa`/`.vtt`, any case) in [movie]'s own folder and in its
         * `subs/` subfolder, the two places release groups put them. Sorted by path so the track
         * order is stable from one open to the next.
         */
        fun externalSubtitles(movie: File): List<File> {
            val folder = movie.absoluteFile.parentFile ?: return emptyList()
            return listOf(folder, File(folder, SUBS_DIR_NAME))
                .flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
                .filter { it.isFile && it.extension.lowercase() in SUBTITLE_EXTENSIONS }
                .sortedBy { it.path }
        }
    }
}
