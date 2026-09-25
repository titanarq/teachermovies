package com.teachermovies.mobile.di

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.teachermovies.discovery.client.AndroidNsdBrowser
import com.teachermovies.discovery.client.NsdServiceDiscoverer
import com.teachermovies.discovery.client.ServiceDiscoverer
import com.teachermovies.mobile.api.KtorTvApi
import com.teachermovies.mobile.api.TvApi
import com.teachermovies.mobile.data.DataStorePairedTvStore
import com.teachermovies.mobile.data.PairedTvStore
import com.teachermovies.mobile.send.MagnetSender
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

// One DataStore per file per process: the delegate hands out the same instance every time.
private val Context.pairedTvDataStore: DataStore<Preferences> by preferencesDataStore(name = "paired_tv")

/**
 * The phone app's object graph, built by hand (ADR-0003): constructor injection only, created once
 * by `MobileApp` and exposing each collaborator as its interface where it has one, so no caller can
 * reach an implementation. No fake is wired in production code.
 */
class MobileContainer(
    application: Application,
) {
    /** Browses the LAN for the TV's `_http._tcp` service over Android NSD. */
    val serviceDiscoverer: ServiceDiscoverer = NsdServiceDiscoverer(AndroidNsdBrowser(application))

    // One HTTP client (CIO engine) for the process; `KtorTvApi` derives its configured client from it.
    private val httpClient: HttpClient = HttpClient(CIO)

    /** The TV's HTTP API; one instance serves any TV, the base URL is passed per call. */
    val tvApi: TvApi = KtorTvApi(httpClient)

    /** The one paired TV and its token, in the `paired_tv` Preferences DataStore. */
    val pairedTvStore: PairedTvStore = DataStorePairedTvStore(application.pairedTvDataStore)

    /** Sends a magnet to the paired TV (#198): what the downloads screen's field calls. */
    val magnetSender: MagnetSender = MagnetSender(tvApi, pairedTvStore)

    /** Sent as `deviceName` to `POST /api/pair`; this is the only place `Build.MODEL` is read. */
    val deviceName: String = Build.MODEL ?: DEFAULT_DEVICE_NAME

    private companion object {
        const val DEFAULT_DEVICE_NAME = "Android"
    }
}
