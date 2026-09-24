package com.teachermovies.torrent.service

import com.teachermovies.torrent.api.TorrentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob

/**
 * The single owner of the process's [TorrentEngine], shared by [TorrentService] and whatever else
 * in the process (the app's container, the HTTP server) talks to the engine.
 *
 * The application calls [init] once, before starting [TorrentService] (#55). Calling it again with
 * the same instance is a no-op; with a different one it fails, so two engines can never race for
 * the same session and state directory.
 */
object TorrentEngineHolder {
    @Volatile
    private var current: TorrentEngine? = null

    /**
     * Serial scope for the engine's lifecycle calls (`start`, `saveResumeData` + `stop`). It
     * outlives any one service instance, so the shutdown launched from `onDestroy` is not cancelled
     * with the service, and a service restarted right away queues its `start()` behind that stop.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal val lifecycleScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    val isInitialized: Boolean
        get() = current != null

    /** The engine passed to [init]; throws [IllegalStateException] before that. */
    val engine: TorrentEngine
        get() = checkNotNull(current) { "TorrentEngineHolder.init(engine) has not been called" }

    @Synchronized
    fun init(engine: TorrentEngine) {
        val existing = current
        check(existing == null || existing === engine) {
            "TorrentEngineHolder is already initialised with a different engine"
        }
        current = engine
    }
}
