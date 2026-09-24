package com.teachermovies.torrent.sync

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.Torrent
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.TorrentRepository
import com.teachermovies.torrent.api.EngineResult
import com.teachermovies.torrent.api.TorrentEngine
import com.teachermovies.torrent.api.TorrentSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/**
 * Keeps [TorrentRepository] in sync with what [engine] reports (#68), so the TV screens and the
 * Library survive process death and restarts: every torrent the engine knows about gets a row, and
 * that row tracks state, progress, bytes, name, save path and main file as they change.
 *
 * [start] collects [TorrentEngine.torrents] on [scope]; for each snapshot whose persisted fields
 * changed since the last write to [repo] ([DownloadState], progress rounded to one decimal,
 * downloaded and total bytes, name, save path, main file index), it calls [TorrentRepository.upsert].
 * A state change is written immediately; anything else is throttled to at most one write every
 * [THROTTLE_MILLIS] per torrent, so a fast-ticking progress stream does not turn into a write per
 * tick -- the next snapshot after the window still carries the latest values, nothing is lost, only
 * coalesced. [stop] cancels the collection without touching anything already written.
 *
 * `mainFilePath` is `savePath/<files[mainFileIndex].path>`, resolved by calling [TorrentEngine.files]
 * the first time a torrent's `mainFileIndex` is seen (cached after that, keyed by that index, since
 * a torrent's file list does not change once metadata is known).
 *
 * A torrent that stops being reported by [engine] -- for instance because the engine is still
 * loading resume data right after a restart -- is left untouched in [repo]: the only path that
 * deletes a row is [remove], which wraps [TorrentEngine.remove] and deletes the row only once the
 * engine confirms the removal. A transient or permanent absence from [TorrentEngine.torrents] that
 * did not go through [remove] never deletes anything.
 *
 * @param clock the current time in epoch milliseconds, injectable so tests run on virtual time.
 */
class EngineRepositorySync(
    private val engine: TorrentEngine,
    private val repo: TorrentRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private var job: Job? = null

    // Written only from the single collector coroutine started by `start`, except `remove`, which
    // clears an id's entries once the engine confirms the torrent is gone.
    private val lastPersisted = mutableMapOf<TorrentId, PersistedFields>()
    private val lastWriteAtMillis = mutableMapOf<TorrentId, Long>()
    private val mainFileRelativePath = mutableMapOf<TorrentId, Pair<Int, String>>()

    /** Starts collecting [TorrentEngine.torrents] into [repo]. Safe to call while already running. */
    fun start() {
        if (job != null) return
        job =
            scope.launch {
                engine.torrents.collect { snapshots ->
                    snapshots.forEach { snapshot -> handleSnapshot(snapshot) }
                }
            }
    }

    /** Stops collecting. Does not delete or otherwise change anything already written to [repo]. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Removes torrent [id] from the engine and, only once that succeeds, deletes its row from
     * [repo]. This -- not a torrent simply missing from [TorrentEngine.torrents] -- is the one path
     * that deletes a row.
     */
    suspend fun remove(
        id: TorrentId,
        deleteFiles: Boolean,
    ): EngineResult<Unit> {
        val result = engine.remove(id, deleteFiles)
        if (result is EngineResult.Ok) {
            repo.delete(id)
            lastPersisted.remove(id)
            lastWriteAtMillis.remove(id)
            mainFileRelativePath.remove(id)
        }
        return result
    }

    private suspend fun handleSnapshot(snapshot: TorrentSnapshot) {
        val fields = snapshot.toPersistedFields()
        val previous = lastPersisted[snapshot.id]
        if (fields == previous) return

        val stateChanged = previous == null || previous.state != fields.state
        val now = clock()
        val lastWrite = lastWriteAtMillis[snapshot.id]
        if (!stateChanged && lastWrite != null && now - lastWrite < THROTTLE_MILLIS) return

        val mainFilePath = resolveMainFilePath(snapshot)
        repo.upsert(fields.toDomain(snapshot.errorMessage), mainFilePath, now)
        lastPersisted[snapshot.id] = fields
        lastWriteAtMillis[snapshot.id] = now
    }

    private suspend fun resolveMainFilePath(snapshot: TorrentSnapshot): String? {
        val mainFileIndex = snapshot.mainFileIndex ?: return null
        val savePath = snapshot.savePath ?: return null
        val cached = mainFileRelativePath[snapshot.id]
        val relativePath =
            if (cached != null && cached.first == mainFileIndex) {
                cached.second
            } else {
                val files = (engine.files(snapshot.id) as? EngineResult.Ok)?.value ?: return null
                val file = files.getOrNull(mainFileIndex) ?: return null
                mainFileRelativePath[snapshot.id] = mainFileIndex to file.path
                file.path
            }
        return "$savePath/$relativePath"
    }

    /** The subset of [TorrentSnapshot] that decides whether a write is due; see the class doc. */
    private data class PersistedFields(
        val id: TorrentId,
        val name: String,
        val state: DownloadState,
        val progressPercent: Double,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val savePath: String?,
        val mainFileIndex: Int?,
    ) {
        fun toDomain(errorMessage: String?): Torrent =
            Torrent(
                id = id,
                name = name,
                state = state,
                progressPercent = progressPercent,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                savePath = savePath,
                mainFileIndex = mainFileIndex,
                errorMessage = errorMessage,
            )
    }

    private fun TorrentSnapshot.toPersistedFields() =
        PersistedFields(
            id = id,
            name = name,
            state = state,
            progressPercent = round1(progressPercent),
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            savePath = savePath,
            mainFileIndex = mainFileIndex,
        )

    private fun round1(value: Double): Double = (value * 10.0).roundToLong() / 10.0

    private companion object {
        const val THROTTLE_MILLIS = 5_000L
    }
}
