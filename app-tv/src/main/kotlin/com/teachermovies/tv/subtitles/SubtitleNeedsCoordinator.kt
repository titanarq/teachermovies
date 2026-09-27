package com.teachermovies.tv.subtitles

import com.teachermovies.core.model.LibraryItem
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.TorrentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Nudges the laptop bridge when a movie finishes downloading (#280, ADR-0005 §5): it follows the
 * library and calls [notify] once for every movie that appears in it, which is what makes the HTTP
 * server put a `subtitles-needed` frame on the bridge's stream.
 *
 * The movies already in the library when [start] runs are only recorded, never notified: a nudge
 * means "a download just completed", and a bridge that was off reads the whole needs list when it
 * connects (#282), so replaying history on every app start would only repeat work. A movie that
 * leaves the library and comes back is new again, and is notified again.
 *
 * Which languages a completed movie needs, and its moviehash (#279), belong to whoever writes the
 * fetch-state rows of #274; this class touches no state and only says "look again now".
 */
class SubtitleNeedsCoordinator(
    private val library: TorrentRepository,
    private val notify: (TorrentId) -> Unit,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    /** The library's ids as of its previous emission, or null before the first one arrives. */
    private var previous: Set<TorrentId>? = null

    /** Starts following the library; a no-op if already started. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { library.observeLibrary().collect(::onLibrary) }
    }

    // Only the one collector coroutine ever runs this, so [previous] needs no lock of its own.
    private fun onLibrary(items: List<LibraryItem>) {
        val ids = items.mapTo(mutableSetOf()) { it.id }
        previous?.let { before -> (ids - before).forEach(notify) }
        previous = ids
    }
}
