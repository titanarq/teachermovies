package com.teachermovies.torrent.jlib

import com.frostwire.jlibtorrent.AddTorrentParams
import com.frostwire.jlibtorrent.AlertListener
import com.frostwire.jlibtorrent.InfoHash
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionHandle
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.Sha1Hash
import com.frostwire.jlibtorrent.Sha256Hash
import com.frostwire.jlibtorrent.TorrentFlags
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.TorrentStatus
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
import com.teachermovies.torrent.policy.RawPhase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The jlibtorrent 2.x-backed [TorrentEngine] (ADR-0001 §3-4).
 *
 * Threading: jlibtorrent calls [listener] on its own alert thread. The listener copies what it
 * needs out of each alert into a plain [SessionEvent] (alert objects are only valid during the
 * callback) and posts it into [events]; nothing else happens on that thread. Every piece of mutable
 * state ([session], [snapshots], [ticker]) is read and written only on [serial], a one-at-a-time
 * view of the injected dispatcher, both by the public suspend methods and by the coroutines draining
 * [events] and running the ticker.
 *
 * While running, a ticker on [serial] reads every handle's `torrent_status` once per [TICK_MILLIS]
 * and republishes [torrents] through [StatusSampleMapper]; [stop] cancels it. When a torrent's
 * metadata is known (a magnet's `metadata_received_alert`, or a `.torrent` file's successful add) the
 * engine applies `FileSelectionPolicy` through `prioritize_files` and records the chosen
 * [TorrentSnapshot.mainFileIndex]. Control operations act on the session's handle for the id and
 * republish at once; unknown ids answer [EngineError.UnknownTorrent]. Window prioritisation (E7) and
 * resume data (#53) answer [EngineError.Unsupported] until they land.
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
    private var ticker: Job? = null

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
                ticker = scope.launch { tick() }
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
            ticker?.cancel()
            ticker = null
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

    /** [EngineError.NotReady] until the torrent has metadata and a live handle. */
    override suspend fun files(id: TorrentId): EngineResult<List<TorrentFileInfo>> =
        onHandle(id, needsMetadata = true) { handle ->
            val storage = handle.torrentFile()?.files() ?: return@onHandle failure(EngineError.NotReady)
            val count = storage.numFiles()
            EngineResult.Ok(
                StatusSampleMapper.fileInfos(
                    paths = List(count) { storage.filePath(it) },
                    sizes = List(count) { storage.fileSize(it) },
                    libPriorities = handle.filePriorities().map(Priority::swig),
                    progress = handle.fileProgress().toList(),
                ),
            )
        }

    /**
     * Skip/Normal/High become libtorrent priority 0/4/7 ([StatusSampleMapper.libPriorityOf]). Indices
     * outside the torrent's file list are ignored, as they are by the fake.
     */
    override suspend fun setFilePriorities(
        id: TorrentId,
        priorities: Map<Int, FilePriority>,
    ): EngineResult<Unit> =
        onHandle(id, needsMetadata = true) { handle ->
            val count = handle.torrentFile()?.numFiles() ?: return@onHandle failure(EngineError.NotReady)
            for ((index, priority) in priorities) {
                if (index in 0 until count) handle.filePriority(index, Priority.fromSwig(StatusSampleMapper.libPriorityOf(priority)))
            }
            refreshAndPublish(id)
            EngineResult.Ok(Unit)
        }

    /** Takes the torrent out of libtorrent's auto-management so the queue never resumes it. */
    override suspend fun pause(id: TorrentId): EngineResult<Unit> =
        onHandle(id) { handle ->
            handle.unsetFlags(TorrentFlags.AUTO_MANAGED)
            handle.pause()
            refreshAndPublish(id)
            EngineResult.Ok(Unit)
        }

    /** Resumes the torrent under manual control (not auto-managed), matching [pause]. */
    override suspend fun resume(id: TorrentId): EngineResult<Unit> =
        onHandle(id) { handle ->
            handle.unsetFlags(TorrentFlags.AUTO_MANAGED)
            handle.resume()
            refreshAndPublish(id)
            EngineResult.Ok(Unit)
        }

    /**
     * Removes the torrent from the session (with `SessionHandle.DELETE_FILES` when [deleteFiles]) and
     * from [torrents]. A torrent the session never accepted (its add failed) is simply forgotten.
     */
    override suspend fun remove(
        id: TorrentId,
        deleteFiles: Boolean,
    ): EngineResult<Unit> =
        withContext(serial) {
            if (id !in snapshots) return@withContext failure(EngineError.UnknownTorrent)
            val manager = session ?: return@withContext failure(EngineError.NotReady)
            val handle = handleOf(id)
            if (handle != null) {
                try {
                    if (deleteFiles) manager.remove(handle, SessionHandle.DELETE_FILES) else manager.remove(handle)
                } catch (e: RuntimeException) {
                    return@withContext nativeFailure(e)
                }
            }
            snapshots.remove(id)
            publish()
            EngineResult.Ok(Unit)
        }

    override suspend fun saveResumeData(): EngineResult<Unit> = EngineResult.Failure(EngineError.Unsupported)

    /** Window prioritisation is epic E7 (#8): [EngineError.Unsupported] for a known torrent. */
    override suspend fun prioritizeWindow(
        id: TorrentId,
        fileIndex: Int,
        byteOffset: Long,
        windowBytes: Long,
    ): EngineResult<Unit> = unsupported(id)

    /** Window prioritisation is epic E7 (#8): [EngineError.Unsupported] for a known torrent. */
    override suspend fun clearWindow(id: TorrentId): EngineResult<Unit> = unsupported(id)

    // -- alert thread: copy plain values out, touch nothing else --------------------------------

    private fun addedEvent(alert: AddTorrentAlert): SessionEvent? {
        val hashes = alert.params().getInfoHashes()
        val id = idOf(hashes) ?: return null
        val error = alert.error()
        if (error.isError) return SessionEvent.Added(id, errorMessage = error.message(), files = null)
        val info = alert.handle().takeIf { it.isValid }?.torrentFile()
        return SessionEvent.Added(id, errorMessage = null, files = info?.let(::filesOf))
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
            files = info?.let(::filesOf).orEmpty(),
        )
    }

    private fun idOf(hashes: InfoHash): TorrentId? =
        JlibMappers.torrentIdOf(
            v1Hex = if (hashes.hasV1()) hashes.v1.toHex() else null,
            v2Hex = if (hashes.hasV2()) hashes.v2.toHex() else null,
        )

    /** [info]'s file list as plain values, in torrent order, for the automatic selection. */
    private fun filesOf(info: TorrentInfo): List<TorrentFileInfo> {
        val storage = info.files()
        return List(storage.numFiles()) { index ->
            TorrentFileInfo(
                index = index,
                path = storage.filePath(index),
                sizeBytes = storage.fileSize(index),
                priority = FilePriority.Normal,
                downloadedBytes = 0,
            )
        }
    }

    // -- serial: the only place state changes ---------------------------------------------------

    private fun apply(event: SessionEvent) {
        val current = snapshots[event.id] ?: return
        snapshots[event.id] =
            when (event) {
                is SessionEvent.Added ->
                    when {
                        event.errorMessage != null -> JlibMappers.withAddError(current, event.errorMessage)
                        event.files != null -> withAutoSelection(current, event.files)
                        else -> current
                    }
                is SessionEvent.MetadataReceived ->
                    withAutoSelection(JlibMappers.withMetadata(current, event.name, event.totalBytes, event.savePath), event.files)
            }
        publish()
    }

    /**
     * Applies `FileSelectionPolicy` (via [StatusSampleMapper.autoSelection]) to the torrent's handle
     * and records the chosen main file. A native failure marks the torrent errored with its message.
     */
    private fun withAutoSelection(
        snapshot: TorrentSnapshot,
        files: List<TorrentFileInfo>,
    ): TorrentSnapshot {
        if (files.isEmpty()) return snapshot
        val handle = handleOf(snapshot.id) ?: return snapshot
        val selection = StatusSampleMapper.autoSelection(files)
        return try {
            handle.prioritizeFiles(selection.libPriorities.map(Priority::fromSwig).toTypedArray())
            snapshot.copy(mainFileIndex = selection.mainFileIndex)
        } catch (e: RuntimeException) {
            JlibMappers.withAddError(snapshot, e.message ?: e.javaClass.name)
        }
    }

    /** The ticker: once per [TICK_MILLIS], every known torrent is re-read from its handle. */
    private suspend fun tick() {
        while (currentCoroutineContext().isActive) {
            delay(TICK_MILLIS)
            for (id in snapshots.keys.toList()) refresh(id)
            publish()
        }
    }

    /**
     * Re-reads torrent [id]'s status into its snapshot; callers [publish]. A torrent without a live
     * handle (its add is still pending, or the session refused it) keeps its current snapshot; a
     * native failure while reading marks it errored until a later read succeeds.
     */
    private fun refresh(id: TorrentId) {
        val current = snapshots[id] ?: return
        val handle = handleOf(id) ?: return
        snapshots[id] =
            try {
                val torrentStatus = handle.status()
                val savePath = if (current.savePath == null && torrentStatus.hasMetadata()) handle.savePath() else current.savePath
                StatusSampleMapper.apply(current, sampleOf(torrentStatus, savePath))
            } catch (e: RuntimeException) {
                JlibMappers.withAddError(current, e.message ?: e.javaClass.name)
            }
    }

    private fun sampleOf(
        torrentStatus: TorrentStatus,
        savePath: String?,
    ): StatusSample {
        val flags = torrentStatus.flags()
        val error = torrentStatus.errorCode()
        return StatusSample(
            phase = phaseOf(torrentStatus.state()),
            paused = flags.and_(TorrentFlags.PAUSED).nonZero(),
            autoManaged = flags.and_(TorrentFlags.AUTO_MANAGED).nonZero(),
            hasMetadata = torrentStatus.hasMetadata(),
            isFinished = torrentStatus.isFinished(),
            errorMessage = if (error.isError) error.message() else null,
            progress = torrentStatus.progress(),
            totalWanted = torrentStatus.totalWanted(),
            totalWantedDone = torrentStatus.totalWantedDone(),
            downloadRate = torrentStatus.downloadRate().toLong(),
            uploadRate = torrentStatus.uploadRate().toLong(),
            numPeers = torrentStatus.numPeers(),
            allTimeUpload = torrentStatus.allTimeUpload(),
            allTimeDownload = torrentStatus.allTimeDownload(),
            savePath = savePath,
        )
    }

    private fun phaseOf(state: TorrentStatus.State): RawPhase =
        when (state) {
            TorrentStatus.State.CHECKING_FILES -> RawPhase.CheckingFiles
            TorrentStatus.State.DOWNLOADING_METADATA -> RawPhase.DownloadingMetadata
            TorrentStatus.State.DOWNLOADING -> RawPhase.Downloading
            TorrentStatus.State.FINISHED -> RawPhase.Finished
            TorrentStatus.State.SEEDING -> RawPhase.Seeding
            TorrentStatus.State.CHECKING_RESUME_DATA -> RawPhase.CheckingResumeData
            TorrentStatus.State.UNKNOWN -> RawPhase.Unknown
        }

    /** The session's live handle for [id] (a v1 or v2 info-hash), or null when there is none. */
    private fun handleOf(id: TorrentId): TorrentHandle? {
        val manager = session ?: return null
        val handle =
            if (id.value.length == SHA1_HEX_LENGTH) manager.find(Sha1Hash(id.value)) else manager.find(Sha256Hash(id.value))
        return handle?.takeIf { it.isValid }
    }

    private fun publish() {
        torrentList.value = snapshots.values.toList()
    }

    private fun failStart(e: Throwable): EngineResult<Unit> {
        status.value = EngineStatus.Error
        return failure(EngineError.Io(e.message ?: e.javaClass.name))
    }

    /**
     * Runs [block] on [serial] with torrent [id]'s live handle: [EngineError.UnknownTorrent] for an id
     * the engine does not know, [EngineError.NotReady] while the session is stopped, the add is still
     * pending, or ([needsMetadata]) the metadata has not arrived; a native failure becomes
     * [EngineError.Io].
     */
    private suspend fun <T> onHandle(
        id: TorrentId,
        needsMetadata: Boolean = false,
        block: (TorrentHandle) -> EngineResult<T>,
    ): EngineResult<T> =
        withContext(serial) {
            val snapshot = snapshots[id] ?: return@withContext failure(EngineError.UnknownTorrent)
            if (needsMetadata && !snapshot.hasMetadata) return@withContext failure(EngineError.NotReady)
            val handle = handleOf(id) ?: return@withContext failure(EngineError.NotReady)
            try {
                block(handle)
            } catch (e: RuntimeException) {
                nativeFailure(e)
            }
        }

    private fun refreshAndPublish(id: TorrentId) {
        refresh(id)
        publish()
    }

    private fun nativeFailure(e: RuntimeException): EngineResult<Nothing> = failure(EngineError.Io(e.message ?: e.javaClass.name))

    private suspend fun <T> unsupported(id: TorrentId): EngineResult<T> =
        withContext(serial) {
            if (id in snapshots) failure(EngineError.Unsupported) else failure(EngineError.UnknownTorrent)
        }

    private fun failure(error: EngineError): EngineResult<Nothing> = EngineResult.Failure(error)

    private companion object {
        /** How often the ticker republishes [torrents]. */
        const val TICK_MILLIS = 1_000L

        const val SHA1_HEX_LENGTH = 40
    }
}

/** What the alert thread hands to [JLibTorrentEngine]'s serial side: plain values only. */
internal sealed interface SessionEvent {
    val id: TorrentId

    /**
     * An `add_torrent_alert`; [errorMessage] is set when the session refused the torrent, [files] when
     * it was added with its metadata already known (a `.torrent` file).
     */
    data class Added(
        override val id: TorrentId,
        val errorMessage: String?,
        val files: List<TorrentFileInfo>?,
    ) : SessionEvent

    /** A `metadata_received_alert`: the magnet's metadata arrived from peers, with its [files]. */
    data class MetadataReceived(
        override val id: TorrentId,
        val name: String?,
        val totalBytes: Long,
        val savePath: String?,
        val files: List<TorrentFileInfo>,
    ) : SessionEvent
}
