package com.teachermovies.torrent.jlib

import com.frostwire.jlibtorrent.AddTorrentParams
import com.frostwire.jlibtorrent.AlertListener
import com.frostwire.jlibtorrent.InfoHash
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.alerts.AddTorrentAlert
import com.frostwire.jlibtorrent.alerts.Alert
import com.frostwire.jlibtorrent.alerts.AlertType
import com.frostwire.jlibtorrent.alerts.MetadataReceivedAlert
import com.teachermovies.core.model.TorrentId
import com.teachermovies.torrent.api.EngineError
import com.teachermovies.torrent.api.EngineResult
import com.teachermovies.torrent.api.EngineStatus
import com.teachermovies.torrent.api.FilePriority
import com.teachermovies.torrent.api.MagnetUri
import com.teachermovies.torrent.api.SavePathProvider
import com.teachermovies.torrent.api.TorrentEngine
import com.teachermovies.torrent.api.TorrentFileInfo
import com.teachermovies.torrent.api.TorrentSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The jlibtorrent 2.x-backed [TorrentEngine] (ADR-0001 §3-4).
 *
 * Threading: jlibtorrent calls [listener] on its own alert thread. The listener copies what it
 * needs out of each alert into a plain [SessionEvent] (alert objects are only valid during the
 * callback) and posts it into [events]; nothing else happens on that thread. Every piece of mutable
 * state ([session], [snapshots]) is read and written only on [serial], a one-at-a-time view of the
 * injected dispatcher, both by the public suspend methods and by the coroutine draining [events].
 *
 * This slice covers the session lifecycle and adding torrents (#51). Per-torrent control, stats and
 * file lists (#52) and resume data (#53) answer [EngineError.Unsupported] for a known torrent until
 * they land.
 *
 * @param savePaths the per-torrent payload directory.
 * @param stateDir where the engine keeps its own state (session and resume data, #53).
 * @param dispatcher where the engine's bookkeeping runs; it is confined to one task at a time.
 */
class JLibTorrentEngine(
    private val savePaths: SavePathProvider,
    private val stateDir: File,
    dispatcher: CoroutineDispatcher,
) : TorrentEngine {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val serial: CoroutineDispatcher = dispatcher.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serial)

    /** The only thing the alert thread touches; unlimited, so `trySend` never fails while open. */
    private val events = Channel<SessionEvent>(Channel.UNLIMITED)

    private val status = MutableStateFlow(EngineStatus.Stopped)
    private val torrentList = MutableStateFlow<List<TorrentSnapshot>>(emptyList())

    // Confined to [serial].
    private var session: SessionManager? = null
    private val snapshots = LinkedHashMap<TorrentId, TorrentSnapshot>()

    private val listener =
        object : AlertListener {
            override fun types(): IntArray = intArrayOf(AlertType.ADD_TORRENT.swig(), AlertType.METADATA_RECEIVED.swig())

            override fun alert(alert: Alert<*>) {
                val event =
                    when (alert) {
                        is AddTorrentAlert -> addedEvent(alert)
                        is MetadataReceivedAlert -> metadataEvent(alert)
                        else -> null
                    }
                if (event != null) events.trySend(event)
            }
        }

    init {
        scope.launch {
            for (event in events) apply(event)
        }
    }

    override val engineStatus: StateFlow<EngineStatus> = status.asStateFlow()

    override val torrents: StateFlow<List<TorrentSnapshot>> = torrentList.asStateFlow()

    override suspend fun start(): EngineResult<Unit> =
        withContext(serial) {
            if (session != null) return@withContext EngineResult.Ok(Unit)
            status.value = EngineStatus.Starting
            try {
                stateDir.mkdirs()
                val settings =
                    SettingsPack()
                        .listenInterfaces(JlibMappers.listenInterfaces())
                        .also { it.setEnableDht(true) }
                val manager = SessionManager(false)
                manager.addListener(listener)
                manager.start(SessionParams(settings))
                session = manager
                status.value = EngineStatus.Running
                EngineResult.Ok(Unit)
            } catch (e: Exception) {
                failStart(e)
            } catch (e: LinkageError) {
                // UnsatisfiedLinkError: the native library for this ABI is missing or broken.
                failStart(e)
            }
        }

    override suspend fun stop() {
        withContext(serial) {
            session?.let { manager ->
                session = null
                manager.removeListener(listener)
                manager.stop()
            }
            status.value = EngineStatus.Stopped
        }
    }

    override suspend fun addMagnet(uri: String): EngineResult<TorrentId> =
        withContext(serial) {
            val magnet = MagnetUri.parse(uri) ?: return@withContext failure(EngineError.InvalidMagnet)
            val id = TorrentId(magnet.infoHash)
            if (id in snapshots) return@withContext failure(EngineError.AlreadyExists(id))
            val manager = session ?: return@withContext failure(EngineError.NotReady)
            val params =
                try {
                    AddTorrentParams.parseMagnetUri(uri)
                } catch (e: IllegalArgumentException) {
                    return@withContext failure(EngineError.InvalidMagnet)
                }
            params.savePath(savePaths.savePathFor(id).absolutePath)
            manager.swig().async_add_torrent(params.swig())
            snapshots[id] = JlibMappers.addedSnapshot(id, magnet.displayName, hasMetadata = false, totalBytes = 0, savePath = null)
            publish()
            EngineResult.Ok(id)
        }

    override suspend fun addTorrentFile(bytes: ByteArray): EngineResult<TorrentId> =
        withContext(serial) {
            val info =
                try {
                    TorrentInfo.bdecode(bytes)
                } catch (e: IllegalArgumentException) {
                    return@withContext failure(EngineError.InvalidTorrentFile)
                }
            val id =
                JlibMappers.torrentIdOf(info.infoHashV1()?.toHex(), info.infoHashV2()?.toHex())
                    ?: return@withContext failure(EngineError.InvalidTorrentFile)
            if (id in snapshots) return@withContext failure(EngineError.AlreadyExists(id))
            val manager = session ?: return@withContext failure(EngineError.NotReady)
            val savePath = savePaths.savePathFor(id).absolutePath
            val params = AddTorrentParams.createInstance()
            params.torrentInfo(info)
            params.savePath(savePath)
            manager.swig().async_add_torrent(params.swig())
            snapshots[id] = JlibMappers.addedSnapshot(id, info.name(), hasMetadata = true, totalBytes = info.totalSize(), savePath = savePath)
            publish()
            EngineResult.Ok(id)
        }

    override suspend fun files(id: TorrentId): EngineResult<List<TorrentFileInfo>> = notYet(id)

    override suspend fun setFilePriorities(
        id: TorrentId,
        priorities: Map<Int, FilePriority>,
    ): EngineResult<Unit> = notYet(id)

    override suspend fun pause(id: TorrentId): EngineResult<Unit> = notYet(id)

    override suspend fun resume(id: TorrentId): EngineResult<Unit> = notYet(id)

    override suspend fun remove(
        id: TorrentId,
        deleteFiles: Boolean,
    ): EngineResult<Unit> = notYet(id)

    override suspend fun saveResumeData(): EngineResult<Unit> = EngineResult.Failure(EngineError.Unsupported)

    override suspend fun prioritizeWindow(
        id: TorrentId,
        fileIndex: Int,
        byteOffset: Long,
        windowBytes: Long,
    ): EngineResult<Unit> = notYet(id)

    override suspend fun clearWindow(id: TorrentId): EngineResult<Unit> = notYet(id)

    // -- alert thread: copy plain values out, touch nothing else --------------------------------

    private fun addedEvent(alert: AddTorrentAlert): SessionEvent? {
        val hashes = alert.params().getInfoHashes()
        val id = idOf(hashes) ?: return null
        val error = alert.error()
        return SessionEvent.Added(id, errorMessage = if (error.isError) error.message() else null)
    }

    private fun metadataEvent(alert: MetadataReceivedAlert): SessionEvent? {
        val handle = alert.handle()
        val id = idOf(InfoHash(handle.swig().info_hashes())) ?: return null
        val info = handle.torrentFile()
        return SessionEvent.MetadataReceived(
            id = id,
            name = info?.name() ?: handle.name(),
            totalBytes = info?.totalSize() ?: 0,
            savePath = handle.savePath(),
        )
    }

    private fun idOf(hashes: InfoHash): TorrentId? =
        JlibMappers.torrentIdOf(
            v1Hex = if (hashes.hasV1()) hashes.v1.toHex() else null,
            v2Hex = if (hashes.hasV2()) hashes.v2.toHex() else null,
        )

    // -- serial: the only place state changes ---------------------------------------------------

    private fun apply(event: SessionEvent) {
        val current = snapshots[event.id] ?: return
        snapshots[event.id] =
            when (event) {
                is SessionEvent.Added -> event.errorMessage?.let { JlibMappers.withAddError(current, it) } ?: current
                is SessionEvent.MetadataReceived -> JlibMappers.withMetadata(current, event.name, event.totalBytes, event.savePath)
            }
        publish()
    }

    private fun publish() {
        torrentList.value = snapshots.values.toList()
    }

    private fun failStart(e: Throwable): EngineResult<Unit> {
        status.value = EngineStatus.Error
        return failure(EngineError.Io(e.message ?: e.javaClass.name))
    }

    private suspend fun <T> notYet(id: TorrentId): EngineResult<T> =
        withContext(serial) {
            if (id in snapshots) failure(EngineError.Unsupported) else failure(EngineError.UnknownTorrent)
        }

    private fun failure(error: EngineError): EngineResult<Nothing> = EngineResult.Failure(error)
}

/** What the alert thread hands to [JLibTorrentEngine]'s serial side: plain values only. */
internal sealed interface SessionEvent {
    val id: TorrentId

    /** An `add_torrent_alert`; [errorMessage] is set when the session refused the torrent. */
    data class Added(
        override val id: TorrentId,
        val errorMessage: String?,
    ) : SessionEvent

    /** A `metadata_received_alert`: the magnet's metadata arrived from peers. */
    data class MetadataReceived(
        override val id: TorrentId,
        val name: String?,
        val totalBytes: Long,
        val savePath: String?,
    ) : SessionEvent
}
