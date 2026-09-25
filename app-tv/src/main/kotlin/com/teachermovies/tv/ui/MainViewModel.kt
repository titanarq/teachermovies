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

    data class Player(
        val id: TorrentId,
    ) : AppRoute {
        override val route: String = "player/${id.value}"

        companion object {
            const val PATTERN = "player/{id}"
        }
    }
}

/**
 * The shell's whole state: which section the tab row has selected, and whether the player route
 * ([AppRoute.Player]) has replaced the shell.
 *
 * [restoreFocusTo] is the Biblioteca card that should take D-pad focus the next time the shell is
 * shown (#249): set when BACK leaves the player that was opened on the Biblioteca section, cleared
 * once the screen has asked for that focus ([MainViewModel.focusRestored]) or the section changes.
 */
data class MainUiState(
    val selected: Destination = Destination.Library,
    val route: AppRoute = AppRoute.Shell,
    val restoreFocusTo: TorrentId? = null,
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
        _uiState.update { state ->
            if (state.selected == destination) state else state.copy(selected = destination, restoreFocusTo = null)
        }
    }

    /** Navigates to `player/{id}` (#72); the selected section is kept for the way back. */
    fun openPlayer(id: TorrentId) {
        _uiState.update { it.copy(route = AppRoute.Player(id)) }
    }

    /**
     * Leaves the player route, back to the shell on the section it was left from. From Biblioteca
     * the played movie's card is remembered in [MainUiState.restoreFocusTo] so focus returns to it
     * rather than to the tab row (#249).
     */
    fun closePlayer() {
        _uiState.update { state ->
            val played = (state.route as? AppRoute.Player)?.id
            state.copy(
                route = AppRoute.Shell,
                restoreFocusTo = played?.takeIf { state.selected == Destination.Library },
            )
        }
    }

    /** The screen has asked for focus on [MainUiState.restoreFocusTo] (or given up on it): forget it. */
    fun focusRestored() {
        _uiState.update { it.copy(restoreFocusTo = null) }
    }
}
