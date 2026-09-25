package com.teachermovies.player.session

import com.teachermovies.core.model.LibraryItem
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.TorrentRepository
import com.teachermovies.player.api.Player
import com.teachermovies.player.api.PlayerState
import com.teachermovies.player.policy.ResumePolicy
import com.teachermovies.player.policy.TrackPolicy
import com.teachermovies.player.streaming.StreamResult
import com.teachermovies.player.streaming.StreamingPlaybackController
import com.teachermovies.torrent.api.EngineError
import com.teachermovies.torrent.api.EngineResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

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

    /**
     * Whether saves take the subtitle from the player's selection rather than the persisted id: only
     * once the persisted track has been re-applied (or there was none, or the selection changed after
     * the wait for it). Separate from [tracksApplied] because libVLC may publish a media's subtitle
     * tracks -- the sidecar files added as slaves -- after its audio tracks (#248).
     */
    @Volatile private var subtitleApplied = false

    @Volatile private var lastSavedAtMs = 0L

    /** The controller supervising the item opened with [openStreaming]; null for a plain [open]. */
    @Volatile private var streaming: StreamingPlaybackController? = null

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

        player.open(file, startPosition(item))
        begin(item, file)
        return SessionResult.Opened(item)
    }

    /**
     * Plays [id] while it is still downloading, through [controller], closing any item this session
     * had open first.
     *
     * The torrent's main file and its index in the torrent come from the repository, and the main
     * file's final size from [StreamingPlaybackController.fileSizeBytes] (the engine's file list --
     * never the torrent's `totalBytes`, which for a multi-file torrent includes its other files);
     * [controller] opens the file (as a growing one) at [ResumePolicy.startPosition] of
     * the persisted position once the ranges it needs are on disk, and supervises it from then on.
     * After that, everything [open] does for a finished item applies unchanged: external subtitles
     * are added, [TrackPolicy] is applied once the tracks are known and progress is saved.
     *
     * Returns [SessionResult.NotFound] for an unknown [id], [SessionResult.FileMissing] when the
     * torrent has no main file selected yet or the main file's size is not known yet (metadata still
     * arriving), and [SessionResult.StreamingFailed] when [controller]
     * answers anything but [StreamResult.Opened] (or the engine fails the size lookup with
     * `UnknownTorrent`/`Unsupported`/`Io`); the player is never opened in those cases.
     * Suspends while [controller] waits for the ranges. [close] also stops [controller].
     */
    suspend fun openStreaming(
        id: TorrentId,
        controller: StreamingPlaybackController,
    ): SessionResult {
        close()
        val torrent = repo.get(id) ?: return SessionResult.NotFound
        val item = repo.getPlaybackItem(id)
        val fileIndex = torrent.mainFileIndex
        if (item == null || fileIndex == null) {
            return SessionResult.FileMissing(item?.mainFilePath ?: torrent.savePath.orEmpty())
        }
        val file = File(item.mainFilePath)
        // The main file's own size, not the torrent's `totalBytes` (item.sizeBytes): a multi-file
        // torrent also counts its sample, subtitles and extras there.
        val fileSizeBytes =
            when (val size = controller.fileSizeBytes(id, fileIndex)) {
                is EngineResult.Ok -> {
                    size.value
                }

                is EngineResult.Failure -> {
                    return when (val error = size.error) {
                        EngineError.NotReady -> SessionResult.FileMissing(item.mainFilePath)

                        EngineError.UnknownTorrent -> SessionResult.StreamingFailed(StreamResult.UnknownTorrent)

                        EngineError.Unsupported -> SessionResult.StreamingFailed(StreamResult.Unsupported)

                        is EngineError.Io -> SessionResult.StreamingFailed(StreamResult.Failed(error.message))

                        // `files` never answers these; mapped all the same so nothing throws.
                        EngineError.InvalidMagnet,
                        EngineError.InvalidTorrentFile,
                        is EngineError.AlreadyExists,
                        -> SessionResult.StreamingFailed(StreamResult.Failed(error.toString()))
                    }
                }
            }

        val result =
            controller.start(
                id = id,
                fileIndex = fileIndex,
                file = file,
                fileSizeBytes = fileSizeBytes,
                // Not stored in the library; the controller uses the player's once it is parsed.
                durationMs = 0L,
                startPositionMs = startPosition(item),
            )
        when (result) {
            StreamResult.Opened -> Unit

            StreamResult.UnknownTorrent,
            StreamResult.Unsupported,
            is StreamResult.Failed,
            -> return SessionResult.StreamingFailed(result)
        }
        streaming = controller
        begin(item, file)
        return SessionResult.Opened(item)
    }

    // The library does not store the media's length, so only the "barely started" rule applies
    // here; the end-of-media rule is covered by storing 0 when playback ends.
    private fun startPosition(item: LibraryItem): Long =
        ResumePolicy.startPosition(item.lastPositionMs, durationMs = null)

    /** What follows opening [file], whoever opened it: subtitles, then watching the player. */
    private fun begin(
        item: LibraryItem,
        file: File,
    ) {
        externalSubtitles(file).forEach { player.addExternalSubtitle(it, select = false) }
        current = item
        tracksApplied = false
        subtitleApplied = false
        lastSavedAtMs = clock()
        job = scope.launch { watch(item) }
    }

    /**
     * Stops watching the player (and the streaming controller, after [openStreaming]), saves the current position and tracks (`0` if playback had
     * ended) and releases the player. A no-op when nothing is open; [open] may be called again.
     */
    suspend fun close() {
        val item = current ?: return
        job?.cancelAndJoin()
        job = null
        current = null
        streaming?.stop()
        streaming = null
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
     * Waits for the player to report the media's audio tracks and applies [TrackPolicy] with the
     * persisted audio id; then re-applies the persisted subtitle id once that track is published,
     * and from then on saves whenever the viewer picks another track.
     *
     * libVLC publishes the tracks as it discovers them (one `ESAdded` each), so the subtitle list
     * can still lack the persisted track -- a sidecar file added as a slave -- when audio first
     * appears (#248). The persisted subtitle is therefore awaited on its own, for at most
     * [SUBTITLE_TRACK_TIMEOUT_MS]; until it is applied, saves keep writing the persisted id, so a
     * track that is merely not published yet is never stored as "off". When it never shows up, the
     * persisted id is kept until the selection changes.
     */
    private suspend fun applyTracksWhenKnown(item: LibraryItem) {
        player.audioTracks.first { it.isNotEmpty() }
        TrackPolicy.audio(player.audioTracks.value, item.audioTrackId)?.let(player::selectAudio)
        tracksApplied = true

        val persistedSubtitle = item.subtitleTrackId
        if (persistedSubtitle == null) {
            player.selectSubtitle(null)
            subtitleApplied = true
        } else {
            val published =
                withTimeoutOrNull(SUBTITLE_TRACK_TIMEOUT_MS) {
                    player.subtitleTracks.first { TrackPolicy.subtitle(it, persistedSubtitle) != null }
                }
            if (published != null) {
                player.selectSubtitle(persistedSubtitle)
                subtitleApplied = true
            }
        }

        combine(player.selectedAudioId, player.selectedSubtitleId) { audio, sub -> audio to sub }
            .distinctUntilChanged()
            // The first value is the selection just applied, not a change by the viewer.
            .drop(1)
            .collect {
                subtitleApplied = true
                save(item, player.positionMs.value)
            }
    }

    /**
     * Writes [positionMs] and the chosen tracks: the player's selection once [TrackPolicy] has been
     * applied (the subtitle once the persisted track has been re-applied), the persisted ids before
     * that, so a save while still opening never clears them.
     */
    private suspend fun save(
        item: LibraryItem,
        positionMs: Long,
    ) {
        val audio = if (tracksApplied) player.selectedAudioId.value else item.audioTrackId
        val subtitle = if (subtitleApplied) player.selectedSubtitleId.value else item.subtitleTrackId
        lastSavedAtMs = clock()
        repo.updatePlayback(item.id, positionMs, audio, subtitle)
    }

    internal companion object {
        /** How often progress is written while playing. */
        const val SAVE_INTERVAL_MS = 5_000L

        /** How long the persisted subtitle track is awaited after the audio tracks appeared. */
        const val SUBTITLE_TRACK_TIMEOUT_MS = 10_000L

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
