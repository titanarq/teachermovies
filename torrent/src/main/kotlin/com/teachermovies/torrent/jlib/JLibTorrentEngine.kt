package com.teachermovies.torrent.jlib

import android.util.Log
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
import com.frostwire.jlibtorrent.Vectors
import com.frostwire.jlibtorrent.alerts.AddTorrentAlert
import com.frostwire.jlibtorrent.alerts.Alert
import com.frostwire.jlibtorrent.alerts.AlertType
import com.frostwire.jlibtorrent.alerts.MetadataReceivedAlert
import com.frostwire.jlibtorrent.alerts.SaveResumeDataAlert
import com.frostwire.jlibtorrent.alerts.SaveResumeDataFailedAlert
import com.frostwire.jlibtorrent.alerts.TorrentDeleteFailedAlert
import com.frostwire.jlibtorrent.alerts.TorrentDeletedAlert
import com.frostwire.jlibtorrent.swig.error_code
import com.frostwire.jlibtorrent.swig.libtorrent
import com.teachermovies.core.model.TorrentId
import com.teachermovies.torrent.api.EngineError
import com.teachermovies.torrent.api.EngineResult
import com.teachermovies.torrent.api.EngineStatus
import com.teachermovies.torrent.api.FilePriority
import com.teachermovies.torrent.api.MagnetUri
import com.teachermovies.torrent.api.PieceRange
import com.teachermovies.torrent.api.PieceWindowCalculator
import com.teachermovies.torrent.api.RangeReadiness
import com.teachermovies.torrent.api.SavePathProvider
import com.teachermovies.torrent.api.TorrentEngine
import com.teachermovies.torrent.api.TorrentFileInfo
import com.teachermovies.torrent.api.TorrentSnapshot
import com.teachermovies.torrent.policy.RawPhase
import com.teachermovies.torrent.resume.FileResumeDataStore
import com.teachermovies.torrent.resume.ResumeDataStore
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

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
 * republish at once; unknown ids answer [EngineError.UnknownTorrent].
 *
 * Read-ahead window (#94): [prioritizeWindow] maps the byte window to pieces
 * ([PieceWindowCalculator]) and applies only the delta against the window it last set for that
 * torrent ([WindowDeadlinePlanner]): pieces that entered get piece priority 7 and a piece deadline
 * ([deadlineStepMs] for the first, one step more for each following one), pieces that left get
 * their deadline reset and priority 4. [clearWindow] drops every deadline; [rangeReadiness] answers
 * from `have_piece`.
 *
 * Resume data (#53): the engine asks libtorrent for a torrent's resume data (with its info dict, so
 * a magnet never re-fetches metadata) every [RESUME_SAVE_MILLIS] for the torrents that need it,
 * right after an add, a metadata arrival and a [pause], and for every torrent in [saveResumeData]
 * and [stop]; each `save_resume_data_alert` is written through [resumeData], and [remove] deletes
 * the entry. [start] re-adds every stored torrent before reporting Running; the flags stored with it
 * (paused, auto-managed) come back as they were, and its file priorities are kept rather than
 * re-running the automatic selection. Entries the store or libtorrent cannot read are skipped and
 * left on disk untouched.
 *
 * @param savePaths the per-torrent payload directory.
 * @param stateDir where the engine keeps its own state; resume data lives in `<stateDir>/resume`
 *   unless [resumeData] says otherwise.
 * @param dispatcher where the engine's bookkeeping runs; it is confined to one task at a time.
 * @param resumeData where resume data is persisted.
 * @param deadlineStepMs the deadline step, in milliseconds, between consecutive pieces entering the
 *   read-ahead window; smaller is more aggressive.
 */
class JLibTorrentEngine(
    private val savePaths: SavePathProvider,
    private val stateDir: File,
    dispatcher: CoroutineDispatcher,
    private val resumeData: ResumeDataStore = FileResumeDataStore(File(stateDir, RESUME_DIR)),
    private val deadlineStepMs: Int = DEFAULT_DEADLINE_STEP_MS,
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
    private var resumeTicker: Job? = null

    /** The read-ahead window last applied per torrent by [prioritizeWindow]. */
    private val windows = HashMap<TorrentId, PieceRange>()

    /** Torrents re-added from resume data whose `add_torrent_alert` has not arrived yet. */
    private val restored = HashSet<TorrentId>()

    /** Resume-data requests libtorrent has not answered yet (saved or failed). */
    private val pendingResumeSaves = MutableStateFlow(0)

    /** Resume-data failures (native or store writes) since the last [saveResumeData] began. */
    private val resumeFailures = ArrayList<String>()

    /** Save directories to remove once libtorrent confirms a delete with files (#229). */
    private val dirCleanup = TorrentDirCleanup()

    private val listener =
        object : AlertListener {
            override fun types(): IntArray =
                intArrayOf(
                    AlertType.ADD_TORRENT.swig(),
                    AlertType.METADATA_RECEIVED.swig(),
                    AlertType.SAVE_RESUME_DATA.swig(),
                    AlertType.SAVE_RESUME_DATA_FAILED.swig(),
                    AlertType.TORRENT_DELETED.swig(),
                    AlertType.TORRENT_DELETE_FAILED.swig(),
                )

            override fun alert(alert: Alert<*>) {
                val event =
                    when (alert) {
                        is AddTorrentAlert -> addedEvent(alert)

                        is MetadataReceivedAlert -> metadataEvent(alert)

                        is SaveResumeDataAlert -> resumeDataEvent(alert)

                        // libtorrent also answers this way when there was nothing to save or the
                        // torrent is gone; it only settles the pending request.
                        is SaveResumeDataFailedAlert -> SessionEvent.ResumeDataFailed(handleIdOf(alert.handle()))

                        is TorrentDeletedAlert -> idOf(alert.getInfoHashes())?.let(SessionEvent::FilesDeleted)

                        is TorrentDeleteFailedAlert -> deleteFailedEvent(alert)

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
                restoreStored(manager)
                publish()
                ticker = scope.launch { tick() }
                resumeTicker = scope.launch { saveResumeDataPeriodically() }
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
            resumeTicker?.cancel()
            resumeTicker = null
            session?.let { manager ->
                // Final resume data: pause the session so nothing changes underneath (each torrent
                // keeps its own paused flag), then wait, bounded, for every answer to be written.
                try {
                    manager.pause()
                    requestResumeDataForAll()
                } catch (e: RuntimeException) {
                    resumeFailures += e.message ?: e.javaClass.name
                }
                awaitResumeSaves()
                session = null
                manager.removeListener(listener)
                manager.stop()
            }
            pendingResumeSaves.value = 0
            restored.clear()
            // Deadlines live in the session; a restarted session starts without any.
            windows.clear()
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
            snapshots[id] =
                JlibMappers.addedSnapshot(id, magnet.displayName, hasMetadata = false, totalBytes = 0, savePath = null)
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
            snapshots[id] =
                JlibMappers.addedSnapshot(
                    id,
                    info.name(),
                    hasMetadata = true,
                    totalBytes = info.totalSize(),
                    savePath = savePath,
                )
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
                if (index in
                    0 until count
                ) {
                    handle.filePriority(index, Priority.fromSwig(StatusSampleMapper.libPriorityOf(priority)))
                }
            }
            refreshAndPublish(id)
            EngineResult.Ok(Unit)
        }

    /** Takes the torrent out of libtorrent's auto-management so the queue never resumes it. */
    override suspend fun pause(id: TorrentId): EngineResult<Unit> =
        onHandle(id) { handle ->
            handle.unsetFlags(TorrentFlags.AUTO_MANAGED)
            handle.pause()
            requestResumeData(handle)
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
     * With [deleteFiles], once libtorrent confirms the files are gone its save directory
     * (`<volume>/Movies/<id>/`) is removed too, only if empty ([TorrentDirCleanup], #229).
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
                // Recorded before the remove: the deleted alert is applied on [serial] after this block.
                dirCleanup.removeRequested(id, saveDirOf(handle), deleteFiles)
                try {
                    if (deleteFiles) manager.remove(handle, SessionHandle.DELETE_FILES) else manager.remove(handle)
                } catch (e: RuntimeException) {
                    return@withContext nativeFailure(e)
                }
            }
            snapshots.remove(id)
            restored.remove(id)
            windows.remove(id)
            publish()
            try {
                resumeData.delete(id)
                EngineResult.Ok(Unit)
            } catch (e: IOException) {
                failure(EngineError.Io(e.message ?: e.javaClass.name))
            }
        }

    /**
     * Asks libtorrent for every live torrent's resume data and waits (at most
     * [RESUME_WAIT_MILLIS]) until each answer has been written. Ok while stopped: [stop] already
     * saved everything. [EngineError.Io] when a save failed or libtorrent did not answer in time.
     */
    override suspend fun saveResumeData(): EngineResult<Unit> =
        withContext(serial) {
            if (session == null) return@withContext EngineResult.Ok(Unit)
            resumeFailures.clear()
            try {
                requestResumeDataForAll()
            } catch (e: RuntimeException) {
                return@withContext nativeFailure(e)
            }
            val answered = awaitResumeSaves()
            val failures = resumeFailures.toList()
            resumeFailures.clear()
            when {
                failures.isNotEmpty() -> failure(EngineError.Io(failures.first()))
                !answered -> failure(EngineError.Io("resume data not saved within $RESUME_WAIT_MILLIS ms"))
                else -> EngineResult.Ok(Unit)
            }
        }

    /**
     * Slides torrent [id]'s read-ahead window to the pieces covering `[byteOffset, byteOffset +
     * windowBytes)` of file [fileIndex], applying only [WindowDeadlinePlanner.plan]'s delta against
     * the window set last: entering pieces get priority 7 and a deadline, leaving pieces get their
     * deadline reset and priority 4, and every other piece is left alone (the same window applies
     * nothing). [EngineError.NotReady] before metadata or for a file index the torrent does not have.
     */
    override suspend fun prioritizeWindow(
        id: TorrentId,
        fileIndex: Int,
        byteOffset: Long,
        windowBytes: Long,
    ): EngineResult<Unit> =
        onHandle(id, needsMetadata = true) { handle ->
            val layout = layoutOf(handle, fileIndex) ?: return@onHandle failure(EngineError.NotReady)
            val current = layout.piecesFor(byteOffset, windowBytes)
            val plan = WindowDeadlinePlanner.plan(windows[id], current, deadlineStepMs)
            for (piece in plan.reset) {
                handle.resetPieceDeadline(piece)
                handle.piecePriority(piece, Priority.fromSwig(DEFAULT_PIECE_PRIORITY))
            }
            for ((piece, deadlineMs) in plan.deadlines) {
                handle.piecePriority(piece, Priority.fromSwig(WINDOW_PIECE_PRIORITY))
                handle.setPieceDeadline(piece, deadlineMs)
            }
            windows[id] = current
            EngineResult.Ok(Unit)
        }

    /**
     * Drops every piece deadline of torrent [id], returns the pieces of the remembered window to
     * priority 4 and forgets it; Ok when no window was set. [EngineError.NotReady] before metadata.
     */
    override suspend fun clearWindow(id: TorrentId): EngineResult<Unit> =
        onHandle(id, needsMetadata = true) { handle ->
            handle.clearPieceDeadlines()
            windows[id]?.let { window ->
                for (piece in window.firstPiece..window.lastPiece) {
                    handle.piecePriority(piece, Priority.fromSwig(DEFAULT_PIECE_PRIORITY))
                }
            }
            windows.remove(id)
            EngineResult.Ok(Unit)
        }

    /**
     * Answers from [readinessFor] over the covering pieces, with `have_piece` saying which are on
     * disk; `readyBytes` never runs past the end of the file. [EngineError.NotReady] before metadata
     * or for a file index the torrent does not have.
     */
    override suspend fun rangeReadiness(
        id: TorrentId,
        fileIndex: Int,
        byteOffset: Long,
        lengthBytes: Long,
    ): EngineResult<RangeReadiness> =
        onHandle(id, needsMetadata = true) { handle ->
            val layout = layoutOf(handle, fileIndex) ?: return@onHandle failure(EngineError.NotReady)
            val length = lengthBytes.coerceAtMost(layout.fileSizeBytes - byteOffset)
            EngineResult.Ok(
                readinessFor(
                    range = layout.piecesFor(byteOffset, length),
                    pieceLengthBytes = layout.pieceLengthBytes,
                    fileOffsetInTorrent = layout.fileOffsetInTorrent,
                    byteOffset = byteOffset,
                    lengthBytes = length,
                    have = handle::havePiece,
                ),
            )
        }

    // -- alert thread: copy plain values out, touch nothing else --------------------------------

    private fun addedEvent(alert: AddTorrentAlert): SessionEvent? {
        val hashes = alert.params().getInfoHashes()
        val id = idOf(hashes) ?: return null
        val error = alert.error()
        if (error.isError) return SessionEvent.Added(id, errorMessage = error.message(), files = null)
        val info = alert.handle().takeIf { it.isValid }?.torrentFile()
        return SessionEvent.Added(id, errorMessage = null, files = info?.let(::filesOf))
    }

    /** Serialises the alert's resume data now: its params are only valid during the callback. */
    private fun resumeDataEvent(alert: SaveResumeDataAlert): SessionEvent {
        val params = alert.params()
        val id = idOf(params.getInfoHashes()) ?: return SessionEvent.ResumeDataFailed(null)
        return try {
            SessionEvent.ResumeDataSaved(
                id,
                Vectors.byte_vector2bytes(libtorrent.write_resume_data_buf_ex(params.swig())),
            )
        } catch (e: RuntimeException) {
            SessionEvent.ResumeDataFailed(id, e.message ?: e.javaClass.name)
        }
    }

    /** Only a v1 hash is carried; a v2-only torrent's cleanup entry is then simply never used. */
    private fun deleteFailedEvent(alert: TorrentDeleteFailedAlert): SessionEvent? =
        JlibMappers.torrentIdOf(v1Hex = alert.getInfoHash().toHex(), v2Hex = null)?.let(SessionEvent::FilesDeleteFailed)

    /** [handle]'s save path, or null when libtorrent cannot report it (the dir is then kept). */
    private fun saveDirOf(handle: TorrentHandle): File? =
        try {
            handle.savePath()?.takeIf { it.isNotBlank() }?.let(::File)
        } catch (e: RuntimeException) {
            null
        }

    /** The id of [handle]'s torrent, or null when the handle is no longer valid (it was removed). */
    private fun handleIdOf(handle: TorrentHandle): TorrentId? =
        try {
            if (handle.isValid) idOf(InfoHash(handle.swig().info_hashes())) else null
        } catch (e: RuntimeException) {
            null
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
        when (event) {
            is SessionEvent.Added -> {
                applyAdded(event)
            }

            is SessionEvent.MetadataReceived -> {
                applyMetadata(event)
            }

            is SessionEvent.ResumeDataSaved -> {
                resumeSaveAnswered()
                // An answer arriving after [remove] must not bring the entry back.
                if (event.id !in snapshots) return
                try {
                    resumeData.save(event.id, event.bytes)
                } catch (e: IOException) {
                    resumeFailures += e.message ?: e.javaClass.name
                }
            }

            is SessionEvent.ResumeDataFailed -> {
                resumeSaveAnswered()
                if (event.message != null) resumeFailures += event.message
            }

            is SessionEvent.FilesDeleted -> {
                dirCleanup.filesDeleted(event.id)
            }

            is SessionEvent.FilesDeleteFailed -> {
                dirCleanup.deleteFailed(event.id)
            }
        }
    }

    private fun applyAdded(event: SessionEvent.Added) {
        val current = snapshots[event.id] ?: return
        val wasRestored = restored.remove(event.id)
        snapshots[event.id] =
            when {
                event.errorMessage != null -> {
                    JlibMappers.withAddError(current, event.errorMessage)
                }

                // A restored torrent keeps the file priorities stored in its resume data.
                wasRestored && event.files != null -> {
                    current.copy(mainFileIndex = StatusSampleMapper.autoSelection(event.files).mainFileIndex)
                }

                event.files != null -> {
                    withAutoSelection(current, event.files)
                }

                else -> {
                    current
                }
            }
        if (event.errorMessage == null) {
            // A restored torrent shows its stored state (e.g. paused) at once; a new one is
            // persisted right away so it survives a crash before the next periodic save.
            if (wasRestored) refresh(event.id) else handleOf(event.id)?.let(::requestResumeDataSafely)
        }
        publish()
    }

    private fun applyMetadata(event: SessionEvent.MetadataReceived) {
        val current = snapshots[event.id] ?: return
        snapshots[event.id] =
            withAutoSelection(
                JlibMappers.withMetadata(current, event.name, event.totalBytes, event.savePath),
                event.files,
            )
        // Save the info dict now, so a restart never has to fetch the metadata again.
        handleOf(event.id)?.let(::requestResumeDataSafely)
        publish()
    }

    // -- resume data (serial) --------------------------------------------------------------------

    /**
     * Re-adds every torrent [resumeData] holds, before [start] reports Running. An entry libtorrent
     * cannot parse, or whose info-hash does not match its name, is skipped and left on disk.
     */
    private fun restoreStored(manager: SessionManager) {
        for ((id, bytes) in resumeData.load().entries) {
            val params =
                try {
                    val error = error_code()
                    val raw = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(bytes), error)
                    if (error.failed()) null else AddTorrentParams(raw)
                } catch (e: RuntimeException) {
                    null
                } ?: continue
            if (idOf(params.getInfoHashes()) != id) continue
            if (params.savePath().isNullOrBlank()) params.savePath(savePaths.savePathFor(id).absolutePath)
            val info = params.torrentInfo()
            manager.swig().async_add_torrent(params.swig())
            restored += id
            if (id !in snapshots) {
                snapshots[id] =
                    JlibMappers.addedSnapshot(
                        id = id,
                        name = params.name()?.takeIf { it.isNotBlank() } ?: info?.name(),
                        hasMetadata = info != null,
                        totalBytes = info?.totalSize() ?: 0,
                        savePath = params.savePath(),
                    )
            }
        }
    }

    /** Every [RESUME_SAVE_MILLIS], asks for the resume data of torrents libtorrent flags as changed. */
    private suspend fun saveResumeDataPeriodically() {
        while (currentCoroutineContext().isActive) {
            delay(RESUME_SAVE_MILLIS)
            for (id in snapshots.keys.toList()) {
                val handle = handleOf(id) ?: continue
                try {
                    if (handle.needSaveResumeData()) requestResumeData(handle)
                } catch (e: RuntimeException) {
                    resumeFailures += e.message ?: e.javaClass.name
                }
            }
        }
    }

    private fun requestResumeDataForAll() {
        for (id in snapshots.keys.toList()) handleOf(id)?.let(::requestResumeData)
    }

    /** Asks libtorrent for [handle]'s resume data (with the info dict); the answer is an alert. */
    private fun requestResumeData(handle: TorrentHandle) {
        handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
        pendingResumeSaves.value += 1
    }

    /** [requestResumeData] for an opportunistic save: a native failure is recorded, not thrown. */
    private fun requestResumeDataSafely(handle: TorrentHandle) {
        try {
            requestResumeData(handle)
        } catch (e: RuntimeException) {
            resumeFailures += e.message ?: e.javaClass.name
        }
    }

    private fun resumeSaveAnswered() {
        if (pendingResumeSaves.value > 0) pendingResumeSaves.value -= 1
    }

    /**
     * Suspends (so [events] keeps draining on [serial]) until every pending request is answered or
     * [RESUME_WAIT_MILLIS] pass; false on timeout.
     */
    private suspend fun awaitResumeSaves(): Boolean =
        withTimeoutOrNull(RESUME_WAIT_MILLIS) { pendingResumeSaves.first { it == 0 } } != null

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
     * native failure while reading marks it errored until a later read succeeds. A state move outside
     * core-model's transition table is logged as a warning and published anyway.
     */
    private fun refresh(id: TorrentId) {
        val current = snapshots[id] ?: return
        val handle = handleOf(id) ?: return
        snapshots[id] =
            try {
                val torrentStatus = handle.status()
                val savePath =
                    if (current.savePath == null &&
                        torrentStatus.hasMetadata()
                    ) {
                        handle.savePath()
                    } else {
                        current.savePath
                    }
                val next = StatusSampleMapper.apply(current, sampleOf(torrentStatus, savePath))
                if (StatusSampleMapper.isUnexpectedTransition(current.state, next.state)) {
                    Log.w(
                        TAG,
                        "Torrent ${id.value}: ${current.state} -> ${next.state} is outside the transition table; " +
                            "publishing it anyway",
                    )
                }
                next
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
            if (id.value.length ==
                SHA1_HEX_LENGTH
            ) {
                manager.find(Sha1Hash(id.value))
            } else {
                manager.find(Sha256Hash(id.value))
            }
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

    /**
     * The piece geometry of [handle]'s torrent as plain values, for file [fileIndex]; null when the
     * metadata is not there or the torrent has no such file.
     */
    private fun layoutOf(
        handle: TorrentHandle,
        fileIndex: Int,
    ): PieceLayout? {
        val info = handle.torrentFile() ?: return null
        val storage = info.files()
        if (fileIndex !in 0 until storage.numFiles()) return null
        return PieceLayout(
            pieceLengthBytes = info.pieceLength(),
            totalPieces = info.numPieces(),
            fileOffsetInTorrent = storage.fileOffset(fileIndex),
            fileSizeBytes = storage.fileSize(fileIndex),
        )
    }

    private fun refreshAndPublish(id: TorrentId) {
        refresh(id)
        publish()
    }

    private fun nativeFailure(e: RuntimeException): EngineResult<Nothing> =
        failure(
            EngineError.Io(e.message ?: e.javaClass.name),
        )

    private fun failure(error: EngineError): EngineResult<Nothing> = EngineResult.Failure(error)

    private companion object {
        const val TAG = "JLibTorrentEngine"

        /** How often the ticker republishes [torrents]. */
        const val TICK_MILLIS = 1_000L

        /** How often torrents whose state changed get their resume data saved. */
        const val RESUME_SAVE_MILLIS = 60_000L

        /** How long [saveResumeData] and [stop] wait for libtorrent to answer every request. */
        const val RESUME_WAIT_MILLIS = 10_000L

        /** `<stateDir>/resume`: the default [FileResumeDataStore] directory. */
        const val RESUME_DIR = "resume"

        const val SHA1_HEX_LENGTH = 40

        /** [deadlineStepMs]'s default: the first piece of a new window is wanted within 100 ms. */
        const val DEFAULT_DEADLINE_STEP_MS = 100

        /** libtorrent's default piece priority, to which pieces leaving the window return. */
        const val DEFAULT_PIECE_PRIORITY = 4

        /** libtorrent's top piece priority, given to pieces inside the window. */
        const val WINDOW_PIECE_PRIORITY = 7
    }
}

/** One file's place in its torrent's pieces, read from `TorrentInfo`: plain values only. */
internal data class PieceLayout(
    val pieceLengthBytes: Int,
    val totalPieces: Int,
    val fileOffsetInTorrent: Long,
    val fileSizeBytes: Long,
) {
    /** The pieces covering `[byteOffset, byteOffset + lengthBytes)` of the file. */
    fun piecesFor(
        byteOffset: Long,
        lengthBytes: Long,
    ): PieceRange =
        PieceWindowCalculator.piecesFor(fileOffsetInTorrent, pieceLengthBytes, totalPieces, byteOffset, lengthBytes)
}

/** What the alert thread hands to [JLibTorrentEngine]'s serial side: plain values only. */
internal sealed interface SessionEvent {
    /** The torrent the event is about, or null when it can no longer be identified. */
    val id: TorrentId?

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

    /** A `save_resume_data_alert`, already serialised to the bencoded [bytes] to store. */
    class ResumeDataSaved(
        override val id: TorrentId,
        val bytes: ByteArray,
    ) : SessionEvent

    /**
     * A `save_resume_data_failed_alert`, or a save that could not be serialised (with its
     * [message]); either way it answers one pending request.
     */
    data class ResumeDataFailed(
        override val id: TorrentId?,
        val message: String? = null,
    ) : SessionEvent

    /** A `torrent_deleted_alert`: libtorrent finished deleting the files of a removed torrent. */
    data class FilesDeleted(
        override val id: TorrentId,
    ) : SessionEvent

    /** A `torrent_delete_failed_alert`: some of a removed torrent's files could not be deleted. */
    data class FilesDeleteFailed(
        override val id: TorrentId,
    ) : SessionEvent
}
