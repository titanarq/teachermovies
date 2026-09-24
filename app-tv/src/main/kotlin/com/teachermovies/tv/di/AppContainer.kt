package com.teachermovies.tv.di

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.teachermovies.core.settings.DataStoreSettingsRepository
import com.teachermovies.core.settings.SettingsRepository
import com.teachermovies.core.settings.settingsDataStore
import com.teachermovies.storage.AndroidStorageVolumeProvider
import com.teachermovies.storage.FileSpaceProvider
import com.teachermovies.storage.SpaceProvider
import com.teachermovies.storage.StorageVolumeProvider
import com.teachermovies.torrent.api.TorrentEngine
import com.teachermovies.torrent.jlib.JLibTorrentEngine
import com.teachermovies.torrent.service.TorrentEngineHolder
import com.teachermovies.tv.net.LanAddressResolver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The whole object graph, built by hand: constructor injection only, no framework and no service
 * locator calls from outside this class (ADR-0003). Implementations are exposed typed as their
 * interfaces so a caller can never reach through to a concrete type.
 *
 * What is wired is what exists. `:http-server` and `:player` still hold nothing but a placeholder
 * -- no interface to bind yet. [torrentEngine] is the jlibtorrent engine, registered in
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

    private companion object {
        const val TORRENT_STATE_DIR = "torrent-state"
    }
}
