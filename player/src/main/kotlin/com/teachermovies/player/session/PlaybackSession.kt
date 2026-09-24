package com.teachermovies.player.session

import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.TorrentRepository
import com.teachermovies.player.api.Player
import com.teachermovies.player.policy.ResumePolicy
import java.io.File
import kotlinx.coroutines.CoroutineScope

/**
 * Plays one library item on [player]: resumes where the viewer left off and adds the subtitle
 * files sitting next to the movie.
 *
 * [scope] runs the session's own collectors and [clock] (epoch milliseconds) paces them; both are
 * injected so a JVM test drives them deterministically.
 */
class PlaybackSession(
    private val player: Player,
    private val repo: TorrentRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    /**
     * Loads [id] into [player].
     *
     * Returns [SessionResult.NotFound] when [id] is not a completed library item and
     * [SessionResult.FileMissing] when its media file is not on disk; the player is left untouched
     * in both cases. Otherwise opens the file at [ResumePolicy.startPosition] of the persisted
     * position and adds every subtitle file found by [externalSubtitles] as an unselected track.
     */
    suspend fun open(id: TorrentId): SessionResult {
        val item = repo.getLibraryItem(id) ?: return SessionResult.NotFound
        val file = File(item.mainFilePath)
        if (!file.isFile) return SessionResult.FileMissing(item.mainFilePath)

        // The library does not store the media's length, so only the "barely started" rule applies
        // here; the end-of-media rule is covered by storing 0 when playback ends.
        player.open(file, ResumePolicy.startPosition(item.lastPositionMs, durationMs = null))
        externalSubtitles(file).forEach { player.addExternalSubtitle(it, select = false) }
        return SessionResult.Opened(item)
    }

    internal companion object {
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
