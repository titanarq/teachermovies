package com.teachermovies.tv.subtitles

import com.teachermovies.assistant.subtitles.MovieHashResult
import com.teachermovies.assistant.subtitles.OpenSubtitlesHash
import com.teachermovies.core.log.AppLog
import com.teachermovies.core.model.LibraryItem
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.SubtitleFetchRepository
import com.teachermovies.core.repo.TorrentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Publishes what every stored movie still needs subtitled, and nudges the laptop bridge when a movie
 * finishes downloading (#280, ADR-0005 §5).
 *
 * On each emission of `observeLibrary()` -- the movies already stored when [start] runs as well as
 * every one that completes later -- it writes both of each movie's fetch-state rows, `"es"` and
 * `"en"`, whatever the movie already carries: an English sidecar next to it or an English text track
 * inside its container no longer cancels the English download, because the assistant wants the
 * OpenSubtitles one for every movie (#359, which supersedes the "English only if the movie has none"
 * of ADR-0005 §5 on this point). Both rows carry the moviehash of #279 whenever the file can be
 * hashed, so `GET /api/bridge/subtitle-needs` can publish them. `ensurePending` leaves a row that
 * already exists as it is, so passing over the whole library again only fills in a hash that could
 * not be computed the first time and otherwise changes nothing -- which is also how a movie stored
 * before this rule existed picks up the `"en"` row it is missing.
 *
 * [notify] is called only for the ids that are new since the previous emission, and only once their
 * rows are on disk: a nudge means "a download just completed and its needs are published", and the
 * HTTP server turns it into a `subtitles-needed` frame on the bridge's stream. The movies already in
 * the library when [start] runs are only recorded, never notified -- a bridge that was off reads the
 * whole needs list when it connects (#282), so replaying history on every app start would only
 * repeat work. A movie that leaves the library and comes back is new again, and is notified again.
 *
 * The rows are written on [dispatcher] (Room and the file system). A movie whose rows cannot be
 * written is logged and skipped: one unreadable file must not stop the coordinator following the
 * library, which is the only thing that keeps later downloads published.
 */
class SubtitleNeedsCoordinator(
    private val library: TorrentRepository,
    private val fetches: SubtitleFetchRepository,
    private val notify: (TorrentId) -> Unit,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var job: Job? = null

    /** The library's ids as of its previous emission, or null before the first one arrives. */
    private var previous: Set<TorrentId>? = null

    /** Starts following the library; a no-op if already started. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { library.observeLibrary().collect { onLibrary(it) } }
    }

    // Only the one collector coroutine ever runs this, so [previous] needs no lock of its own.
    private suspend fun onLibrary(items: List<LibraryItem>) {
        withContext(dispatcher) {
            val now = nowMs()
            items.forEach { writeNeeds(it, now) }
        }
        val ids = items.mapTo(mutableSetOf()) { it.id }
        previous?.let { before -> (ids - before).forEach(notify) }
        previous = ids
    }

    /** Both of [item]'s fetch-state rows, `"es"` and `"en"`: neither depends on what it already has. */
    private suspend fun writeNeeds(
        item: LibraryItem,
        now: Long,
    ) {
        try {
            val hash = movieHashOf(File(item.mainFilePath))
            fetches.ensurePending(item.id, SPANISH, hash, now)
            fetches.ensurePending(item.id, ENGLISH, hash, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(LOG_MODULE, "the subtitle needs of ${item.title} could not be stored", e)
        }
    }

    /** The file's moviehash (#279), or null when it is too short to have one or cannot be read. */
    private fun movieHashOf(file: File): String? =
        when (val result = OpenSubtitlesHash.of(file)) {
            is MovieHashResult.Computed -> result.hash
            is MovieHashResult.TooSmall, is MovieHashResult.Unreadable -> null
        }

    private companion object {
        const val LOG_MODULE = "app-tv"
        const val SPANISH = "es"
        const val ENGLISH = "en"
    }
}
