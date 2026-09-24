package com.teachermovies.tv.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.TorrentRepository
import com.teachermovies.player.api.Player
import com.teachermovies.player.api.PlayerState
import com.teachermovies.player.session.PlaybackSession
import com.teachermovies.player.session.SessionResult
import com.teachermovies.tv.format.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the player screen draws, fully formatted (#78). [progress] (0..1) sizes the overlay's
 * progress bar; [error] replaces the picture with a message; [exited] tells the host the session is
 * closed (position saved) and it can go back to Biblioteca. [tracksPanel] is the audio/subtitle
 * side panel (#79), null while it is closed.
 */
data class PlayerUiState(
    val title: String = "",
    val positionText: String = Formatters.playbackTime(0),
    val durationText: String = Formatters.playbackTime(0),
    val progress: Float = 0f,
    val isPlaying: Boolean = false,
    val overlayVisible: Boolean = true,
    val error: String? = null,
    val exited: Boolean = false,
    val tracksPanel: TracksPanelState? = null,
)

/**
 * State holder for the `player/{id}` route (#78). [open] loads the item through [session]; remote
 * keys arrive as [PlayerAction]s through [onAction], which forwards them to [player] and shows the
 * transport overlay for [OVERLAY_TIMEOUT_MS]. [PlayerAction.Exit] closes the session (which saves
 * the position and releases the player) and then sets [PlayerUiState.exited].
 *
 * [closeScope] is where the session is closed if the ViewModel is cleared without an Exit (the
 * route left some other way): `viewModelScope` is already cancelled by then. It is the scope the
 * session itself runs in.
 */
class PlayerViewModel(
    private val session: PlaybackSession,
    private val player: Player,
    private val closeScope: CoroutineScope? = null,
) : ViewModel() {

    private data class Local(
        val title: String = "",
        val overlayVisible: Boolean = true,
        val error: String? = null,
        val exited: Boolean = false,
        val tracksPanelOpen: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    private val tracks =
        combine(
            player.audioTracks,
            player.subtitleTracks,
            player.selectedAudioId,
            player.selectedSubtitleId,
            TracksPanelState::of,
        )

    val uiState: StateFlow<PlayerUiState> =
        combine(local, player.state, player.positionMs, player.durationMs, tracks) { local, state, position, duration, tracks ->
            val error = errorOf(local, state)
            PlayerUiState(
                title = local.title,
                positionText = Formatters.playbackTime(position),
                durationText = Formatters.playbackTime(duration),
                progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                isPlaying = state == PlayerState.Playing,
                overlayVisible = local.overlayVisible,
                error = error,
                exited = local.exited,
                // An error replaces the picture, panel included.
                tracksPanel = tracks.takeIf { local.tracksPanelOpen && error == null },
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, PlayerUiState())

    private var openJob: Job? = null
    private var hideJob: Job? = null
    private var exiting = false
    private var closed = false

    /** Loads [id]; only the first call does anything (the screen may call it on every composition). */
    fun open(id: TorrentId) {
        if (openJob != null) return
        openJob =
            viewModelScope.launch {
                when (val result = session.open(id)) {
                    is SessionResult.Opened -> local.update { it.copy(title = result.item.title) }
                    SessionResult.NotFound -> local.update { it.copy(error = NOT_FOUND) }
                    is SessionResult.FileMissing -> local.update { it.copy(error = FILE_MISSING) }
                }
            }
        showOverlay()
    }

    /**
     * A remote key was pressed: any key (even one that maps to null) shows the overlay; the action,
     * if any, is forwarded to [player]. Ignored once exiting.
     */
    fun onAction(action: PlayerAction?) {
        if (exiting) return
        showOverlay()
        when (action) {
            PlayerAction.TogglePlayPause -> player.togglePlayPause()
            PlayerAction.Play -> player.play()
            PlayerAction.Pause -> player.pause()
            is PlayerAction.SeekBy -> player.seekBy(action.deltaMs)
            PlayerAction.ShowTracks -> local.update { it.copy(tracksPanelOpen = true) }
            PlayerAction.Exit -> back()
            null -> Unit
        }
    }

    /** BACK: closes the track panel without changing anything if it is open, otherwise [exit]s. */
    fun back() {
        val now = local.value
        if (now.tracksPanelOpen && errorOf(now, player.state.value) == null) closeTracks() else exit()
    }

    /** Makes audio track [id] the active one and closes the panel; the session persists it. */
    fun selectAudio(id: String) {
        if (exiting) return
        player.selectAudio(id)
        closeTracks()
    }

    /** Makes subtitle track [id] the active one (null = `Desactivados`) and closes the panel. */
    fun selectSubtitle(id: String?) {
        if (exiting) return
        player.selectSubtitle(id)
        closeTracks()
    }

    /** Closes the track panel without changing the selection. */
    fun closeTracks() {
        local.update { it.copy(tracksPanelOpen = false) }
    }

    /** Closes the session (saving the position) and then reports [PlayerUiState.exited]. */
    fun exit() {
        if (exiting) return
        exiting = true
        hideJob?.cancel()
        closeTracks()
        viewModelScope.launch {
            // Let a pending open finish so close() sees the item it has to save.
            openJob?.join()
            session.close()
            closed = true
            local.update { it.copy(exited = true) }
        }
    }

    private fun errorOf(
        local: Local,
        state: PlayerState,
    ): String? = local.error ?: (state as? PlayerState.Error)?.let { "$PLAYBACK_ERROR_PREFIX${it.message}" }

    private fun showOverlay() {
        local.update { it.copy(overlayVisible = true) }
        hideJob?.cancel()
        hideJob =
            viewModelScope.launch {
                delay(OVERLAY_TIMEOUT_MS)
                local.update { it.copy(overlayVisible = false) }
            }
    }

    override fun onCleared() {
        if (!closed) closeScope?.launch { session.close() }
    }

    /**
     * Builds the ViewModel from `AppContainer`'s bindings (ADR-0003 rule 1). The session runs on
     * its own main-thread scope, which also closes it if the ViewModel is cleared without an Exit.
     */
    class Factory(
        private val player: Player,
        private val repo: TorrentRepository,
        private val clock: () -> Long = System::currentTimeMillis,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PlayerViewModel::class.java)) {
                "Unknown ViewModel class ${modelClass.name}"
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            return PlayerViewModel(PlaybackSession(player, repo, scope, clock), player, scope) as T
        }
    }

    companion object {
        /** How long the transport overlay stays up after the last key. */
        const val OVERLAY_TIMEOUT_MS = 4_000L

        const val FILE_MISSING = "El archivo no está disponible (¿se ha desconectado el disco?)"
        const val NOT_FOUND = "Esta película ya no está en la biblioteca"
        const val PLAYBACK_ERROR_PREFIX = "No se puede reproducir: "
    }
}
