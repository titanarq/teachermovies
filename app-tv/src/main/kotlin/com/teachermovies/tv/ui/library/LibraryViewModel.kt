package com.teachermovies.tv.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.teachermovies.core.model.LibraryItem
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.TorrentRepository
import com.teachermovies.tv.format.Formatters
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * One movie card of the Biblioteca grid, fully formatted (AGENTS.md: no formatting logic in
 * Compose). [resumeText] is `"Continuar en 1 h 2 min"` when a resume position is stored, and null
 * for a movie never played (or played back to the start).
 */
data class LibraryCard(
    val id: TorrentId,
    val title: String,
    val sizeText: String,
    val resumeText: String?,
)

/** Everything the Biblioteca screen shows: one card per completed movie, newest completed first. */
data class LibraryUiState(
    val items: List<LibraryCard> = emptyList(),
)

/**
 * State holder for the Biblioteca screen (#72): [TorrentRepository.observeLibrary] mapped into
 * [LibraryCard]s, in the repository's order. Opening a movie is not this ViewModel's job -- the
 * screen reports the card's id and the host navigates to the player route (#78).
 */
class LibraryViewModel(
    repo: TorrentRepository,
) : ViewModel() {

    val uiState: StateFlow<LibraryUiState> =
        repo
            .observeLibrary()
            .map { items -> LibraryUiState(items = items.map { it.toCard() }) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    private fun LibraryItem.toCard(): LibraryCard =
        LibraryCard(
            id = id,
            title = title,
            sizeText = Formatters.bytes(sizeBytes),
            resumeText =
                lastPositionMs
                    .takeIf { it > 0 }
                    ?.let { "$RESUME_PREFIX ${Formatters.eta(it / MS_PER_SECOND)}" },
        )

    /** Builds the ViewModel from `AppContainer`'s bindings (ADR-0003 rule 1). */
    class Factory(
        private val repo: TorrentRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
                "Unknown ViewModel class ${modelClass.name}"
            }
            return LibraryViewModel(repo) as T
        }
    }

    private companion object {
        const val RESUME_PREFIX = "Continuar en"
        const val MS_PER_SECOND = 1000L
    }
}
