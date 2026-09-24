package com.teachermovies.tv.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The shell's whole state: which section the tab row has selected. */
data class MainUiState(val selected: Destination = Destination.Library)

/**
 * State holder for the shell. It takes no collaborators yet, so `MainActivity` builds it with the
 * default factory; when a section needs data (#45, #46, #70, #72) the dependencies arrive through
 * a `ViewModelProvider.Factory` built from `AppContainer` (ADR-0003 rule 1).
 */
class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())

    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun select(destination: Destination) {
        _uiState.update { it.copy(selected = destination) }
    }
}
