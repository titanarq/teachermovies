package com.teachermovies.tv.player

import com.teachermovies.assistant.SpanishCueSource
import com.teachermovies.assistant.SpanishTextSource
import com.teachermovies.assistant.subtitles.SubtitleTrack
import com.teachermovies.core.log.AppLog
import com.teachermovies.core.model.TorrentId
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Spanish text of the movie being played, for RIGHT's phrase rewind (#347): a
 * [SpanishTextSource] the one `PhraseRewindController` is built over once, whose timeline is
 * [load]ed per movie (the `SpanishCueTimeline` of #344) and dropped by [clear].
 *
 * [hasTimeline] is false until a [load] found a Spanish subtitle that aligns well enough; [textAt]
 * answers null then, so RIGHT draws nothing and the player says so.
 */
class MovieSpanishText(
    private val build: suspend (TorrentId, File) -> SpanishTextSource?,
) : SpanishTextSource,
    SpanishCueSource {
    @Volatile
    private var timeline: SpanishTextSource? = null

    val hasTimeline: Boolean get() = timeline != null

    override fun textAt(positionMs: Long): String? = timeline?.textAt(positionMs)

    /** The loaded timeline's cues on the playback timeline, for the player-track rewind (#358). */
    override fun playbackCues(): SubtitleTrack? = (timeline as? SpanishCueSource)?.playbackCues()

    /** Builds the timeline of [mediaFile]; a failing build is logged and leaves none. */
    suspend fun load(
        torrentId: TorrentId,
        mediaFile: File,
    ) {
        timeline =
            try {
                build(torrentId, mediaFile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(LOG_MODULE, "Spanish timeline failed; RIGHT shows no Spanish", e)
                null
            }
    }

    fun clear() {
        timeline = null
    }

    private companion object {
        const val LOG_MODULE = "app-tv"
    }
}
