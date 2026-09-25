package com.teachermovies.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val DATA_STORE_NAME = "settings"
private const val MIN_HTTP_PORT = 1024
private const val MAX_HTTP_PORT = 65535

private val HTTP_PORT = intPreferencesKey("httpPort")
private val DOWNLOAD_VOLUME_ID = stringPreferencesKey("downloadVolumeId")
private val AUTH_TOKEN_HASHES = stringSetPreferencesKey("authTokenHashes")
private val FIRST_RUN_COMPLETED = booleanPreferencesKey("firstRunCompleted")
private val AUTOSTART_ON_BOOT = booleanPreferencesKey("autostartOnBoot")
private val TRANSLATION_API_KEY = stringPreferencesKey("translationApiKey")

/** [SettingsRepository] over DataStore Preferences. */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {
    override val settings: Flow<AppSettings> =
        dataStore.data.map { preferences ->
            AppSettings(
                httpPort = preferences[HTTP_PORT] ?: DEFAULT_HTTP_PORT,
                downloadVolumeId = preferences[DOWNLOAD_VOLUME_ID],
                authTokenHashes = preferences[AUTH_TOKEN_HASHES] ?: emptySet(),
                firstRunCompleted = preferences[FIRST_RUN_COMPLETED] ?: false,
                autostartOnBoot = preferences[AUTOSTART_ON_BOOT] ?: false,
                translationApiKey = preferences[TRANSLATION_API_KEY],
            )
        }

    override suspend fun setHttpPort(port: Int) {
        require(port in MIN_HTTP_PORT..MAX_HTTP_PORT) {
            "HTTP port must be in $MIN_HTTP_PORT..$MAX_HTTP_PORT, was $port"
        }
        dataStore.edit { preferences -> preferences[HTTP_PORT] = port }
    }

    override suspend fun setDownloadVolumeId(id: String?) {
        dataStore.edit { preferences ->
            if (id == null) {
                preferences.remove(DOWNLOAD_VOLUME_ID)
            } else {
                preferences[DOWNLOAD_VOLUME_ID] = id
            }
        }
    }

    override suspend fun addAuthTokenHash(hash: String) {
        dataStore.edit { preferences ->
            preferences[AUTH_TOKEN_HASHES] = (preferences[AUTH_TOKEN_HASHES] ?: emptySet()) + hash
        }
    }

    override suspend fun clearAuthTokenHashes() {
        dataStore.edit { preferences -> preferences.remove(AUTH_TOKEN_HASHES) }
    }

    override suspend fun setFirstRunCompleted(done: Boolean) {
        dataStore.edit { preferences -> preferences[FIRST_RUN_COMPLETED] = done }
    }

    override suspend fun setAutostartOnBoot(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[AUTOSTART_ON_BOOT] = enabled }
    }

    override suspend fun setTranslationApiKey(key: String?) {
        dataStore.edit { preferences ->
            if (key.isNullOrBlank()) {
                preferences.remove(TRANSLATION_API_KEY)
            } else {
                preferences[TRANSLATION_API_KEY] = key
            }
        }
    }
}

/**
 * Creates the production settings store, backed by `<app files>/datastore/settings.preferences_pb`.
 *
 * DataStore rejects a second instance over a file one is already active on, so the caller keeps the
 * returned store -- `AppContainer` does, once (ADR-0003).
 */
fun Context.settingsDataStore(): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(produceFile = { preferencesDataStoreFile(DATA_STORE_NAME) })
