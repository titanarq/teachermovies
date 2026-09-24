package com.teachermovies.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import com.teachermovies.tv.ui.MainShell
import com.teachermovies.tv.ui.MainViewModel
import com.teachermovies.tv.ui.firstrun.FirstRunRoute
import com.teachermovies.tv.ui.firstrun.FirstRunViewModel
import com.teachermovies.tv.ui.settings.SettingsRoute
import com.teachermovies.tv.ui.settings.SettingsViewModel

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
 */
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    private val settingsViewModel: SettingsViewModel by viewModels {
        val container = (application as TeacherMoviesApp).container
        SettingsViewModel.Factory(
            settings = container.settingsRepository,
            volumes = container.storageVolumeProvider,
            space = container.spaceProvider,
        )
    }

    private val firstRunViewModel: FirstRunViewModel by viewModels {
        val container = (application as TeacherMoviesApp).container
        FirstRunViewModel.Factory(
            settings = container.settingsRepository,
            volumes = container.storageVolumeProvider,
            space = container.spaceProvider,
            engine = container.torrentEngine,
            lan = container.lanAddressResolver,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val firstRunCompleted by firstRunViewModel.firstRunCompleted.collectAsStateWithLifecycle()
                when (firstRunCompleted) {
                    null -> Unit
                    false -> FirstRunRoute(viewModel = firstRunViewModel)
                    true -> {
                        val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
                        MainShell(
                            uiState = uiState,
                            onSelect = mainViewModel::select,
                            settingsContent = { modifier -> SettingsRoute(viewModel = settingsViewModel, modifier = modifier) },
                        )
                    }
                }
            }
        }
    }
}
