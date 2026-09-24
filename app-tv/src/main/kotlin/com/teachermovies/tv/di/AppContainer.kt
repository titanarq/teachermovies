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

/**
 * The whole object graph, built by hand: constructor injection only, no framework and no service
 * locator calls from outside this class (ADR-0003). Implementations are exposed typed as their
 * interfaces so a caller can never reach through to a concrete type.
 *
 * What is wired is what exists. `:torrent`, `:http-server` and `:player` still hold nothing but a
 * placeholder -- no interface to bind yet. Nothing here invents one, and no fake is wired in
 * production code (ADR-0003 rule 3).
 */
class AppContainer(application: Application) {

    // DataStore rejects a second instance over a file one is already active on, so the store is
    // created once here and kept for the life of the process.
    private val dataStore: DataStore<Preferences> = application.settingsDataStore()

    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(dataStore)

    val spaceProvider: SpaceProvider = FileSpaceProvider()

    val storageVolumeProvider: StorageVolumeProvider = AndroidStorageVolumeProvider(application)
}
