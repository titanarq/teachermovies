package com.teachermovies.assistant

import com.teachermovies.assistant.subtitles.SrtWriter
import com.teachermovies.assistant.subtitles.SubtitleTrack
import com.teachermovies.core.log.AppLog
import com.teachermovies.player.api.ExternalSubtitleResult
import com.teachermovies.player.api.Player
import com.teachermovies.player.session.SubtitleSaveGuard
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException

/** Outcome of asking [RewindSubtitleSession.select] for a language's temporary subtitle track. */
sealed interface TempSubtitleResult {
    /** [trackId] of the player is now the selected subtitle. */
    data class Selected(
        val trackId: String,
    ) : TempSubtitleResult

    /** The movie has no subtitle in [language]; the player's selection is untouched. */
    data class Unavailable(
        val language: SubtitleDisplay,
    ) : TempSubtitleResult
}

/**
 * The player side of a phrase rewind (#358): puts the English or Spanish subtitle on the player as a
 * temporary track and takes it away again.
 *
 * English uses the player track hidden mode read its cues from when there is one (an embedded
 * track); otherwise -- and always for Spanish, whose raw track is not on the playback timeline --
 * the cues are written as an SRT under `<cacheDir>/subtitles/rewind/` and added to the player once
 * per movie ([setMovie] starts a new one). libVLC 3 cannot remove a slave, so ending a temporary
 * track is selecting the previous one again, through [HiddenSubtitleController] so that hidden
 * mode does not turn it back off in between.
 */
class RewindSubtitleSession(
    private val player: Player,
    private val hidden: HiddenSubtitleController,
    private val spanishCues: SpanishCueSource,
    private val cacheDir: File,
) {
    @Volatile private var movieKey: String? = null

    @Volatile private var saveGuard: SubtitleSaveGuard? = null

    /** The track id the cues of each language were added under, for this movie. */
    private val added = mutableMapOf<SubtitleDisplay, String>()

    /** The temporary track currently shown, as [HiddenSubtitleController] knows it. */
    val temporaryId: StateFlow<String?> get() = hidden.temporaryId

    /** Starts the session of [mediaFile] (null = no movie): nothing materialized before is reused. */
    fun setMovie(mediaFile: File?) {
        movieKey = mediaFile?.nameWithoutExtension?.let { UNSAFE.replace(it, "_") }
        added.clear()
    }

    /** Keeps the temporary track out of what the playback session persists; null detaches. */
    fun attachSaveGuard(guard: SubtitleSaveGuard?) {
        saveGuard = guard
    }

    /**
     * Selects the [language] subtitle on the player, [previousId] being what was selected before
     * (null = none) and what the save guard writes in its place.
     */
    suspend fun select(
        language: SubtitleDisplay,
        previousId: String?,
    ): TempSubtitleResult {
        val trackId = trackFor(language) ?: return TempSubtitleResult.Unavailable(language)
        saveGuard?.hold(trackId, previousId)
        hidden.selectTemporary(trackId)
        return TempSubtitleResult.Selected(trackId)
    }

    /** Puts [restoreId] (null = none) back and lets saves follow the player's selection again. */
    fun end(restoreId: String?) {
        hidden.endTemporary(restoreId)
        saveGuard?.release()
    }

    private suspend fun trackFor(language: SubtitleDisplay): String? =
        when (language) {
            SubtitleDisplay.ENGLISH -> {
                hidden.sourceTrackId?.takeIf { id -> listed(id) } ?: materialize(language, hidden.track)
            }

            SubtitleDisplay.SPANISH -> {
                materialize(language, spanishCues.playbackCues())
            }

            SubtitleDisplay.OFF -> {
                null
            }
        }

    private fun listed(id: String): Boolean = player.subtitleTracks.value.any { it.id == id }

    private suspend fun materialize(
        language: SubtitleDisplay,
        cues: SubtitleTrack?,
    ): String? {
        added[language]?.takeIf { listed(it) }?.let { return it }
        if (cues == null || cues.cues.isEmpty()) return null
        val key = movieKey ?: return null
        val file = File(File(File(cacheDir, "subtitles"), "rewind"), "$key.${language.name.lowercase()}.srt")
        try {
            SrtWriter.write(cues, file)
        } catch (e: IOException) {
            AppLog.w(LOG_MODULE, "rewind subtitle ${file.name} not written: ${e.message}")
            return null
        }
        return when (val result = player.addExternalSubtitleTrack(file)) {
            is ExternalSubtitleResult.Added -> {
                added[language] = result.track.id
                result.track.id
            }

            is ExternalSubtitleResult.NotAdded -> {
                AppLog.w(LOG_MODULE, "rewind subtitle ${file.name} not added: ${result.reason}")
                null
            }

            ExternalSubtitleResult.TimedOut -> {
                AppLog.w(LOG_MODULE, "rewind subtitle ${file.name}: the player never published its track")
                null
            }
        }
    }

    private companion object {
        const val LOG_MODULE = "assistant"
        val UNSAFE = Regex("[^A-Za-z0-9._-]")
    }
}
