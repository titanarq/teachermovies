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

/**
 * The single entry point, reached from the TV home screen through the `LEANBACK_LAUNCHER` filter in
 * `AndroidManifest.xml`. A [ComponentActivity] because the shell that fills it is Compose for TV.
 *
 * It owns the [MainViewModel] and passes it nothing: the shell is a pure function of the state the
 * ViewModel exposes plus the one callback that changes it.
 */
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
                MainShell(uiState = uiState, onSelect = mainViewModel::select)
            }
        }
    }
}
