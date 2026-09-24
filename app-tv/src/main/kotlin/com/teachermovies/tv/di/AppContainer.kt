package com.teachermovies.tv.di

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.teachermovies.core.db.TeacherMoviesDatabase
import com.teachermovies.core.repo.RoomTorrentRepository
import com.teachermovies.core.repo.TorrentRepository
import com.teachermovies.core.settings.DataStoreSettingsRepository
import com.teachermovies.core.settings.SettingsRepository
import com.teachermovies.core.settings.settingsDataStore
import com.teachermovies.http.HttpServerController
import com.teachermovies.http.LayoutSubtitleStore
import com.teachermovies.http.LocalHttpServer
import com.teachermovies.http.RunningServer
import com.teachermovies.http.ServerDeps
import com.teachermovies.http.auth.PairingManager
import com.teachermovies.storage.AndroidStorageVolumeProvider
import com.teachermovies.storage.FileSpaceProvider
import com.teachermovies.storage.SpaceInfo
import com.teachermovies.storage.SpaceProvider
import com.teachermovies.storage.StorageVolumeProvider
import com.teachermovies.storage.VolumeSelection
import com.teachermovies.storage.VolumeSelector
import com.teachermovies.torrent.api.TorrentEngine
import com.teachermovies.torrent.jlib.JLibTorrentEngine
import com.teachermovies.torrent.service.TorrentEngineHolder
import com.teachermovies.torrent.sync.EngineRepositorySync
import com.teachermovies.tv.net.LanAddressResolver
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

/**
 * The whole object graph, built by hand: constructor injection only, no framework and no service
 * locator calls from outside this class (ADR-0003). Implementations are exposed typed as their
 * interfaces so a caller can never reach through to a concrete type.
 *
 * What is wired is what exists. [httpServerController] runs the embedded HTTP API (#66), started
 * by `TeacherMoviesApp`. [torrentEngine] is the jlibtorrent engine, registered in
 * [TorrentEngineHolder] so `TorrentService` drives the same instance; this is the only place a
 * `com.teachermovies.torrent.jlib` type is named. No fake is wired in production code (ADR-0003
 * rule 3).
 */
class AppContainer(application: Application) {

    // DataStore rejects a second instance over a file one is already active on, so the store is
    // created once here and kept for the life of the process.
    private val dataStore: DataStore<Preferences> = application.settingsDataStore()

    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(dataStore)

    val spaceProvider: SpaceProvider = FileSpaceProvider()

    val storageVolumeProvider: StorageVolumeProvider = AndroidStorageVolumeProvider(application)

    /**
     * Payload directories: `<selected volume>/Movies/<info-hash>`, the volume re-selected on each
     * add. The engine calls this from its own (IO) dispatcher, never the main thread, so reading
     * the persisted volume id synchronously is fine; DataStore serves it from memory after the
     * first read.
     */
    private val savePathProvider =
        SavePathProviderFactory.create(
            volumes = storageVolumeProvider,
            space = spaceProvider,
            persistedVolumeId = { runBlocking { settingsRepository.settings.first().downloadVolumeId } },
            fallbackRoot = application.filesDir,
        )

    /**
     * Built here, started by `TorrentService` (which `MainActivity` starts): the holder is
     * initialised before the service can ever run (#54).
     */
    val torrentEngine: TorrentEngine =
        JLibTorrentEngine(
            savePaths = savePathProvider,
            stateDir = File(application.filesDir, TORRENT_STATE_DIR),
            dispatcher = Dispatchers.IO,
        ).also(TorrentEngineHolder::init)

    val lanAddressResolver: LanAddressResolver = LanAddressResolver()

    private val torrentDatabase: TeacherMoviesDatabase = TeacherMoviesDatabase.build(application)

    val torrentRepository: TorrentRepository = RoomTorrentRepository(torrentDatabase.torrentDao())

    // Lives for the process (no owner to cancel it): `engineRepositorySync` collects the engine for
    // as long as the process runs, same as `TorrentEngineHolder`'s own scope.
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Keeps [torrentRepository] in sync with [torrentEngine] (#68), so the Downloads and Library
     * screens -- and a process restart -- see what the engine reports. Started here, before any
     * screen can collect it.
     */
    val engineRepositorySync: EngineRepositorySync =
        EngineRepositorySync(
            engine = torrentEngine,
            repo = torrentRepository,
            scope = applicationScope,
            clock = { System.currentTimeMillis() },
        ).also { it.start() }

    // The persisted volume id as last read, so [downloadVolumeSpace] never blocks on DataStore.
    // Null until the first read, which only means the selector's fallback for that instant.
    private val persistedVolumeId: StateFlow<String?> =
        settingsRepository.settings
            .map { it.downloadVolumeId }
            .stateIn(applicationScope, SharingStarted.Eagerly, null)

    /**
     * Free space of the volume downloads go to right now -- the one [VolumeSelector] picks, as in
     * [savePathProvider] -- or null when there is no volume at all (Descargas header, #70).
     */
    fun downloadVolumeSpace(): SpaceInfo? {
        val volume =
            when (val selection = VolumeSelector.select(storageVolumeProvider.volumes(), persistedVolumeId.value, spaceProvider)) {
                is VolumeSelection.Selected -> selection.volume
                is VolumeSelection.PersistedMissing -> selection.fallback
                VolumeSelection.NoneAvailable -> null
            }
        return volume?.let { spaceProvider.spaceOf(it.root) }
    }

    /**
     * PIN pairing and token validation (ADR-0002). One instance for the process, shared by every
     * server restart and by the screens that show [PairingManager.currentPin].
     */
    val pairingManager: PairingManager =
        PairingManager(settings = settingsRepository, random = SecureRandom(), clock = System::currentTimeMillis)

    private val appVersion: String =
        runCatching { application.packageManager.getPackageInfo(application.packageName, 0).versionName }
            .getOrNull() ?: UNKNOWN_VERSION

    private fun serverDeps(): ServerDeps =
        ServerDeps(
            engine = torrentEngine,
            space = ::downloadVolumeSpace,
            appVersion = appVersion,
            clock = System::currentTimeMillis,
            pairing = pairingManager,
            subtitles = LayoutSubtitleStore { id -> SubtitleLayoutResolver.layoutFor(id, torrentEngine.torrents.value) },
        )

    /**
     * The embedded HTTP server on the configured port, restarted when the port setting changes
     * (#65). Built here, started by `TeacherMoviesApp.onCreate`; the foreground `TorrentService`
     * keeps the process -- and so the server -- alive. A bind failure is reported in its `state`.
     */
    val httpServerController: HttpServerController =
        HttpServerController(
            settings = settingsRepository,
            depsFactory = ::serverDeps,
            scope = applicationScope,
            serverFactory = { deps, port ->
                val server = LocalHttpServer(deps, port)
                server.start()
                RunningServer(server::stop)
            },
        )

    private companion object {
        const val TORRENT_STATE_DIR = "torrent-state"
        const val UNKNOWN_VERSION = "unknown"
    }
}
