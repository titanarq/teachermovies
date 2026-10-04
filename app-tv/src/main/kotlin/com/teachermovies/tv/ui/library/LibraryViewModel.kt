package com.teachermovies.tv.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.teachermovies.core.model.LibraryItem
import com.teachermovies.core.model.SubtitleFetch
import com.teachermovies.core.model.SubtitleFetchState
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.SubtitleFetchRepository
import com.teachermovies.core.repo.TorrentRepository
import com.teachermovies.tv.format.Formatters
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * One movie card of the Biblioteca grid, fully formatted (AGENTS.md: no formatting logic in
 * Compose). [resumeText] is `"Continuar en 1 h 2 min"` when a resume position is stored, and null
 * for a movie never played (or played back to the start). [subtitleBadges] are the OpenSubtitles
 * subtitles already on the TV for the movie (#375): some of [LibraryViewModel.BADGE_EN] and
 * [LibraryViewModel.BADGE_ES], always in that order, and empty when there is none.
 */
data class LibraryCard(
    val id: TorrentId,
    val title: String,
    val sizeText: String,
    val resumeText: String?,
    val subtitleBadges: List<String> = emptyList(),
)

/** Everything the Biblioteca screen shows: one card per completed movie, newest completed first. */
data class LibraryUiState(
    val items: List<LibraryCard> = emptyList(),
)

/**
 * State holder for the Biblioteca screen (#72): [TorrentRepository.observeLibrary] mapped into
 * [LibraryCard]s, in the repository's order. Each card also carries its OpenSubtitles badges (#375),
 * read off [SubtitleFetchRepository.observeAll]: a language earns its badge exactly when that movie's
 * fetch-state row for it is [SubtitleFetchState.Downloaded], so a download landing while the screen is
 * open repaints the card without a reopen, and rows of a movie that is not in the library are ignored.
 * Opening a movie is not this ViewModel's job -- the screen reports the card's id and the host
 * navigates to the player route (#78).
 */
class LibraryViewModel(
    repo: TorrentRepository,
    subtitleFetches: SubtitleFetchRepository,
) : ViewModel() {
    val uiState: StateFlow<LibraryUiState> =
        combine(repo.observeLibrary(), subtitleFetches.observeAll()) { items, fetches ->
            val downloaded = fetches.downloadedLanguagesByTorrent()
            LibraryUiState(items = items.map { it.toCard(downloaded) })
        }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    /** The languages with a file on disk, per movie: the only state a badge is read off (#375). */
    private fun List<SubtitleFetch>.downloadedLanguagesByTorrent(): Map<TorrentId, Set<String>> =
        filter { it.state == SubtitleFetchState.Downloaded }
            .groupBy({ it.torrentId }, { it.language })
            .mapValues { (_, languages) -> languages.toSet() }

    private fun LibraryItem.toCard(downloaded: Map<TorrentId, Set<String>>): LibraryCard {
        val languages = downloaded[id].orEmpty()
        return LibraryCard(
            id = id,
            title = title,
            sizeText = Formatters.bytes(sizeBytes),
            resumeText =
                lastPositionMs
                    .takeIf { it > 0 }
                    ?.let { "$RESUME_PREFIX ${Formatters.eta(it / MS_PER_SECOND)}" },
            subtitleBadges =
                listOfNotNull(
                    BADGE_EN.takeIf { LANGUAGE_EN in languages },
                    BADGE_ES.takeIf { LANGUAGE_ES in languages },
                ),
        )
    }

    /** Builds the ViewModel from `AppContainer`'s bindings (ADR-0003 rule 1). */
    class Factory(
        private val repo: TorrentRepository,
        private val subtitleFetches: SubtitleFetchRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
                "Unknown ViewModel class ${modelClass.name}"
            }
            return LibraryViewModel(repo, subtitleFetches) as T
        }
    }

    companion object {
        /** Badge of a movie whose English OpenSubtitles file is downloaded (#375). */
        const val BADGE_EN = "EN"

        /** Badge of a movie whose Spanish OpenSubtitles file is downloaded (#375). */
        const val BADGE_ES = "ES"

        private const val LANGUAGE_EN = "en"
        private const val LANGUAGE_ES = "es"
        private const val RESUME_PREFIX = "Continuar en"
        private const val MS_PER_SECOND = 1000L
    }
}
