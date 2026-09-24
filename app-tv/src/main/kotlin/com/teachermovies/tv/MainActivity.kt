package com.teachermovies.tv

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import com.teachermovies.torrent.service.TorrentService
import com.teachermovies.tv.di.AppContainer
import com.teachermovies.tv.ui.AppRoute
import com.teachermovies.tv.ui.MainShell
import com.teachermovies.tv.ui.MainViewModel
import com.teachermovies.tv.ui.downloads.DownloadsScreen
import com.teachermovies.tv.ui.downloads.DownloadsViewModel
import com.teachermovies.tv.ui.firstrun.FirstRunRoute
import com.teachermovies.tv.ui.firstrun.FirstRunViewModel
import com.teachermovies.tv.ui.library.LibraryRoute
import com.teachermovies.tv.ui.library.LibraryViewModel
import com.teachermovies.tv.ui.player.PlayerPlaceholderScreen
import com.teachermovies.tv.ui.settings.SettingsRoute
import com.teachermovies.tv.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * The single entry point, reached from the TV home screen through the `LEANBACK_LAUNCHER` filter in
 * `AndroidManifest.xml`. A [ComponentActivity] because the shell that fills it is Compose for TV.
 *
 * It owns the [MainViewModel] and passes it nothing: the shell is a pure function of the state the
 * ViewModel exposes plus the one callback that changes it. Section ViewModels that need
 * collaborators are built from the application's `AppContainer` through their factories
 * (ADR-0003 rule 1) and bound into the shell's slots here.
 *
 * Until first-run setup is completed the first-run screen is shown instead of the shell; nothing
 * is shown for the instant before the persisted settings have been read.
 *
 * It also starts the foreground [TorrentService] (the engine it drives was registered in
 * `TorrentEngineHolder` when the application built its `AppContainer`), and on Android 13+ asks
 * for `POST_NOTIFICATIONS` once. A denial is not an error: the service still runs, only its
 * notification is hidden.
 */
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    private val settingsViewModel: SettingsViewModel by viewModels {
        val container = (application as TeacherMoviesApp).container
        SettingsViewModel.Factory(
            settings = container.settingsRepository,
            volumes = container.storageVolumeProvider,
            space = container.spaceProvider,
            pin = container.pairingManager::currentPin,
            serverState = container.httpServerController.state,
            serverUrl = serverUrl(container),
        )
    }

    private val downloadsViewModel: DownloadsViewModel by viewModels {
        val container = (application as TeacherMoviesApp).container
        DownloadsViewModel.Factory(
            engine = container.torrentEngine,
            sync = container.engineRepositorySync,
            serverUrl = serverUrl(container),
            space = container::downloadVolumeSpace,
        )
    }

    private val libraryViewModel: LibraryViewModel by viewModels {
        LibraryViewModel.Factory(repo = (application as TeacherMoviesApp).container.torrentRepository)
    }

    private val firstRunViewModel: FirstRunViewModel by viewModels {
        val container = (application as TeacherMoviesApp).container
        FirstRunViewModel.Factory(
            settings = container.settingsRepository,
            volumes = container.storageVolumeProvider,
            space = container.spaceProvider,
            engine = container.torrentEngine,
            lan = container.lanAddressResolver,
            pin = container.pairingManager::currentPin,
            serverState = container.httpServerController.state,
        )
    }

    // The result is deliberately ignored: granted or denied, nothing else changes.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TorrentService.start(this)
        requestNotificationPermissionOnce()
        setContent {
            MaterialTheme {
                val firstRunCompleted by firstRunViewModel.firstRunCompleted.collectAsStateWithLifecycle()
                when (firstRunCompleted) {
                    null -> Unit
                    false -> FirstRunRoute(viewModel = firstRunViewModel)
                    true -> {
                        val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
                        when (val route = uiState.route) {
                            is AppRoute.Player ->
                                PlayerPlaceholderScreen(
                                    id = route.id,
                                    onBack = mainViewModel::closePlayer,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            AppRoute.Shell ->
                                MainShell(
                                    uiState = uiState,
                                    onSelect = mainViewModel::select,
                                    libraryContent = { modifier ->
                                        LibraryRoute(viewModel = libraryViewModel, onPlay = mainViewModel::openPlayer, modifier = modifier)
                                    },
                                    downloadsContent = { modifier -> DownloadsScreen(viewModel = downloadsViewModel, modifier = modifier) },
                                    settingsContent = { modifier -> SettingsRoute(viewModel = settingsViewModel, modifier = modifier) },
                                )
                        }
                    }
                }
            }
        }
    }

    /**
     * `http://<lan-ip>:<port>` for the Descargas empty state; the port follows the settings, the
     * address is read (off the main thread) whenever they change.
     */
    private fun serverUrl(container: AppContainer): Flow<String?> =
        container.settingsRepository.settings
            .map { settings -> container.lanAddressResolver.current()?.let { "http://$it:${settings.httpPort}" } }
            .flowOn(Dispatchers.IO)

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getPreferences(Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_NOTIFICATION_PERMISSION_ASKED, false)) return
        prefs.edit().putBoolean(PREF_NOTIFICATION_PERMISSION_ASKED, true).apply()
        notificationPermission.launch(permission)
    }

    private companion object {
        const val PREF_NOTIFICATION_PERMISSION_ASKED = "notificationPermissionAsked"
    }
}
