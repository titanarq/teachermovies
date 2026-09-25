package com.teachermovies.mobile.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [PairedTvStore] over Preferences DataStore (#196). A TV is stored only as all three keys
 * together; a partially written entry reads as `null`.
 */
class DataStorePairedTvStore(
    private val dataStore: DataStore<Preferences>,
) : PairedTvStore {
    override val pairedTv: Flow<PairedTv?> =
        dataStore.data.map { prefs ->
            val instanceName = prefs[INSTANCE_NAME]
            val baseUrl = prefs[BASE_URL]
            val token = prefs[TOKEN]
            if (instanceName != null && baseUrl != null && token != null) {
                PairedTv(instanceName = instanceName, baseUrl = baseUrl, token = token)
            } else {
                null
            }
        }

    override suspend fun save(tv: PairedTv) {
        dataStore.edit { prefs ->
            prefs[INSTANCE_NAME] = tv.instanceName
            prefs[BASE_URL] = tv.baseUrl
            prefs[TOKEN] = tv.token
        }
    }

    override suspend fun updateBaseUrl(baseUrl: String) {
        dataStore.edit { prefs ->
            if (prefs[INSTANCE_NAME] != null && prefs[BASE_URL] != null && prefs[TOKEN] != null) {
                prefs[BASE_URL] = baseUrl
            }
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(INSTANCE_NAME)
            prefs.remove(BASE_URL)
            prefs.remove(TOKEN)
        }
    }

    private companion object {
        val INSTANCE_NAME = stringPreferencesKey("paired_tv_instance_name")
        val BASE_URL = stringPreferencesKey("paired_tv_base_url")
        val TOKEN = stringPreferencesKey("paired_tv_token")
    }
}
