package com.teachermovies.tv.ui

import androidx.lifecycle.ViewModel
import com.teachermovies.core.model.TorrentId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The top-level screen the activity shows over the shell: the shell itself, or the player for one
 * library item. [route] is the path-style name of the screen (`player/{id}` for the player).
 */
sealed interface AppRoute {
    val route: String

    data object Shell : AppRoute {
        override val route: String = "shell"
    }

    data class Player(val id: TorrentId) : AppRoute {
        override val route: String = "player/${id.value}"

        companion object {
            const val PATTERN = "player/{id}"
        }
    }
}

/**
 * The shell's whole state: which section the tab row has selected, and whether the player route
 * ([AppRoute.Player]) has replaced the shell.
 */
data class MainUiState(
    val selected: Destination = Destination.Library,
    val route: AppRoute = AppRoute.Shell,
)

/**
 * State holder for the shell. It takes no collaborators, so `MainActivity` builds it with the
 * default factory; sections that need data get their own ViewModels through factories built from
 * `AppContainer` (ADR-0003 rule 1).
 */
class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())

    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun select(destination: Destination) {
        _uiState.update { it.copy(selected = destination) }
    }

    /** Navigates to `player/{id}` (#72); the selected section is kept for the way back. */
    fun openPlayer(id: TorrentId) {
        _uiState.update { it.copy(route = AppRoute.Player(id)) }
    }

    /** Leaves the player route, back to the shell on the section it was left from. */
    fun closePlayer() {
        _uiState.update { it.copy(route = AppRoute.Shell) }
    }
}
