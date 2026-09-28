package com.teachermovies.tv.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.teachermovies.assistant.AssistantSpeechController
import com.teachermovies.assistant.CaptureResult
import com.teachermovies.assistant.HiddenModeResult
import com.teachermovies.assistant.HiddenSubtitleController
import com.teachermovies.assistant.LineCaptureController
import com.teachermovies.assistant.TranslationUiState
import com.teachermovies.assistant.explanation.ExplanationContext
import com.teachermovies.assistant.explanation.ExplanationController
import com.teachermovies.assistant.explanation.ExplanationUiState
import com.teachermovies.assistant.subtitles.SubtitleTrack
import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.TorrentRepository
import com.teachermovies.player.api.Player
import com.teachermovies.player.api.PlayerState
import com.teachermovies.player.session.PlaybackSession
import com.teachermovies.player.session.SessionResult
import com.teachermovies.player.streaming.StreamState
import com.teachermovies.player.streaming.StreamingPlaybackController
import com.teachermovies.tv.format.Formatters
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Everything the player screen draws, fully formatted (#78). [progress] (0..1) sizes the overlay's
 * progress bar; [error] replaces the picture with a message; [exited] tells the host the session is
 * closed (position saved) and it can go back to Biblioteca. [tracksPanel] is the audio/subtitle
 * side panel (#79), null while it is closed.
 *
 * [assistant] is the captured-line overlay (#86), null while no line is captured;
 * [assistantAvailable] says whether hidden English subtitles were found for this movie, and
 * [message] is a short notice shown in the transport overlay (e.g. no line to capture).
 *
 * [streamStatus] (#226) is the streaming controller's `Preparing`/`Buffering` state as text while
 * an in-progress download is being played, null otherwise.
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
    val assistant: AssistantOverlayState? = null,
    val assistantAvailable: Boolean = false,
    val message: String? = null,
    val streamStatus: String? = null,
)

/**
 * The captured-line overlay (#86): the English cue [text] the viewer froze and whether its audio
 * fragment is [replaying] right now.
 *
 * From the assistant's speech (#92): [speaking] while a spoken answer is being said and the Spanish
 * [translation] of the line.
 *
 * [spanishLabel] (#288) says where a `Ready` [translation] came from: the aligned Spanish subtitle
 * ([PlayerViewModel.LABEL_SUBTITLE], or [PlayerViewModel.LABEL_SUBTITLE_LATINO]) or the bridge's AI
 * translation ([PlayerViewModel.LABEL_AI]); null while there is no Spanish line to label.
 *
 * [explanation] (#293) is RIGHT's answer: Claude's explanation of the line, or where it stands.
 *
 * [linesBack] (#339) is how many lines before the captured one the fragment being replayed is: 0
 * while OK repeats the captured line itself, 1 or more once OK pressed in a row has stepped back
 * through the track.
 */
data class AssistantOverlayState(
    val text: String,
    val replaying: Boolean,
    val speaking: Boolean = false,
    val translation: TranslationUiState = TranslationUiState.Idle,
    val spanishLabel: String? = null,
    val explanation: ExplanationUiState = ExplanationUiState.Idle,
    val linesBack: Int = 0,
)

/**
 * State holder for the `player/{id}` route (#78). [open] loads the item through [session]; remote
 * keys arrive as [PlayerAction]s through [onAction], which forwards them to [player] and shows the
 * transport overlay for [OVERLAY_TIMEOUT_MS]. [PlayerAction.Exit] closes the session (which saves
 * the position and releases the player) and then sets [PlayerUiState.exited].
 *
 * The English-learning assistant (#86): opening an item starts [hidden] mode on its media file
 * (the outcome only sets [PlayerUiState.assistantAvailable], it never blocks playback) and
 * [AssistantAction]s arrive through [onAssistantAction], forwarded to [capture]. While a line is
 * captured the transport overlay is hidden and transport actions are ignored.
 *
 * [speech] (#92) holds LEFT's bridge translation: opening an item prepares its speaker once on
 * [prepareDispatcher] without waiting for it. No panel key touches the player, so the movie stays
 * paused until [AssistantAction.DismissOverlay], which also resets [speech].
 *
 * LEFT, [AssistantAction.TranslateLine] (#288, ADR-0005 §6), shows the line in Spanish and says
 * nothing: the Spanish subtitle aligned to it from [spanishLines], labelled [LABEL_SUBTITLE], when
 * one aligns well enough; otherwise [speech]'s translation -- the laptop bridge in production --
 * labelled [LABEL_AI], whose failures keep their own texts ("Traducción no disponible", ...).
 *
 * RIGHT, [AssistantAction.ExplainLine] (#293, ADR-0005 §7), asks [explanations] -- Claude on the
 * laptop through the bridge -- to explain the line with its context (the neighbouring English cues of
 * [hidden]'s track, the title and the aligned Spanish line when there is one) and shows the answer,
 * never spoken. Without [explanations] RIGHT does nothing.
 *
 * Playing an in-progress download (#226): when [streamingController] and [repo] are given, [open]
 * of an item that is not [DownloadState.Completed] goes through [PlaybackSession.openStreaming]
 * with [streamingController], and its `Preparing`/`Buffering` states show as
 * [PlayerUiState.streamStatus]; a completed item (or no controller) uses [PlaybackSession.open].
 * An Exit while the controller is still waiting for the first ranges cancels that wait.
 *
 * [closeScope] is where the session is closed if the ViewModel is cleared without an Exit (the
 * route left some other way): `viewModelScope` is already cancelled by then. It is the scope the
 * session itself runs in.
 */
class PlayerViewModel(
    private val session: PlaybackSession,
    private val player: Player,
    private val hidden: HiddenSubtitleController,
    private val capture: LineCaptureController,
    private val speech: AssistantSpeechController,
    private val closeScope: CoroutineScope? = null,
    private val prepareDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val streamingController: StreamingPlaybackController? = null,
    private val repo: TorrentRepository? = null,
    private val spanishLines: AlignedSpanishSource = AlignedSpanishSource.NONE,
    private val explanations: ExplanationController? = null,
) : ViewModel() {
    /** Where LEFT's Spanish line for the captured cue stands (#288); null until LEFT is pressed. */
    private sealed interface SpanishAnswer {
        /** Looking for an aligned Spanish subtitle line. */
        data object Looking : SpanishAnswer

        data class Aligned(
            val line: AlignedSpanishLine,
        ) : SpanishAnswer

        /** No aligned line: [speech]'s translation is the answer. */
        data object Bridge : SpanishAnswer
    }

    private data class Local(
        val title: String = "",
        val overlayVisible: Boolean = true,
        val error: String? = null,
        val exited: Boolean = false,
        val tracksPanelOpen: Boolean = false,
        val assistantAvailable: Boolean = false,
        val message: String? = null,
        val streaming: Boolean = false,
        val spanish: SpanishAnswer? = null,
        /** RIGHT was pressed and its context (the aligned Spanish line) is still being gathered. */
        val explainGathering: Boolean = false,
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

    private val explanationState = explanations?.state ?: flowOf(ExplanationUiState.Idle)

    /** Whether a fragment is playing and how many lines back from the capture it is (#339). */
    private val replayState = combine(capture.replaying, capture.linesBack, ::Pair)

    private val assistant =
        combine(
            capture.captured,
            replayState,
            speech.state,
            local,
            explanationState,
        ) { line, replay, speech, local, explanation ->
            line?.let {
                val (replaying, linesBack) = replay
                val translation =
                    when (val answer = local.spanish) {
                        null, SpanishAnswer.Bridge -> speech.translation
                        SpanishAnswer.Looking -> TranslationUiState.Loading
                        is SpanishAnswer.Aligned -> TranslationUiState.Ready(answer.line.text)
                    }
                val label =
                    when (val answer = local.spanish) {
                        is SpanishAnswer.Aligned -> if (answer.line.latino) LABEL_SUBTITLE_LATINO else LABEL_SUBTITLE
                        SpanishAnswer.Bridge -> LABEL_AI.takeIf { translation is TranslationUiState.Ready }
                        null, SpanishAnswer.Looking -> null
                    }
                AssistantOverlayState(
                    text = it.cue.text,
                    replaying = replaying,
                    speaking = speech.speaking,
                    translation = translation,
                    spanishLabel = label,
                    explanation = if (local.explainGathering) ExplanationUiState.Thinking(it.cue.text) else explanation,
                    linesBack = linesBack,
                )
            }
        }

    private val streamState = streamingController?.state ?: flowOf(StreamState.Idle)

    private val panels = combine(tracks, assistant, streamState, ::Triple)

    val uiState: StateFlow<PlayerUiState> =
        combine(
            local,
            player.state,
            player.positionMs,
            player.durationMs,
            panels,
        ) { local, state, position, duration, panels ->
            val (tracks, assistant, stream) = panels
            val error = errorOf(local, state)
            // An error replaces the picture, assistant overlay included.
            val overlay = assistant.takeIf { error == null }
            PlayerUiState(
                title = local.title,
                positionText = Formatters.playbackTime(position),
                durationText = Formatters.playbackTime(duration),
                progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                isPlaying = state == PlayerState.Playing,
                // The captured-line overlay hides the transport overlay; a message keeps it up.
                overlayVisible = (local.overlayVisible || local.message != null) && overlay == null,
                error = error,
                exited = local.exited,
                // An error replaces the picture, panel included.
                tracksPanel = tracks.takeIf { local.tracksPanelOpen && error == null },
                assistant = overlay,
                assistantAvailable = local.assistantAvailable,
                message = local.message,
                streamStatus = if (local.streaming && error == null) streamStatusOf(stream) else null,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, PlayerUiState())

    private var openJob: Job? = null
    private var hideJob: Job? = null
    private var messageJob: Job? = null
    private var spanishJob: Job? = null
    private var explainJob: Job? = null

    /** The opened movie, for LEFT's aligned Spanish lookup (#288). */
    private var movie: Pair<TorrentId, File>? = null
    private var exiting = false
    private var closed = false

    /** Loads [id]; only the first call does anything (the screen may call it on every composition). */
    fun open(id: TorrentId) {
        if (openJob != null) return
        // The speaker is optional: prepared once, off the main thread, never awaited by playback.
        viewModelScope.launch { withContext(prepareDispatcher) { speech.prepare() } }
        openJob =
            viewModelScope.launch {
                val controller = streamingController?.takeIf { repo != null && !isCompleted(id) }
                if (controller != null) local.update { it.copy(streaming = true) }
                val result = if (controller != null) session.openStreaming(id, controller) else session.open(id)
                when (result) {
                    is SessionResult.Opened -> {
                        local.update { it.copy(title = result.item.title) }
                        movie = id to File(result.item.mainFilePath)
                        // The subtitle track the viewer chose last time stays on (#248).
                        val started =
                            hidden.start(
                                File(result.item.mainFilePath),
                                viewerSubtitleId = result.item.subtitleTrackId,
                            )
                        val available = started is HiddenModeResult.Started
                        local.update { it.copy(assistantAvailable = available) }
                    }

                    SessionResult.NotFound -> {
                        local.update { it.copy(error = NOT_FOUND) }
                    }

                    is SessionResult.FileMissing -> {
                        local.update { it.copy(error = FILE_MISSING) }
                    }

                    // Only `openStreaming` answers this (#226).
                    is SessionResult.StreamingFailed -> {
                        local.update { it.copy(error = STREAMING_FAILED) }
                    }
                }
            }
        showOverlay()
    }

    /** Whether [id] is a finished download; unknown to [repo] counts as finished (plain `open`). */
    private suspend fun isCompleted(id: TorrentId): Boolean =
        (repo?.get(id)?.state ?: DownloadState.Completed) == DownloadState.Completed

    /**
     * A remote key was pressed: any key (even one that maps to null) shows the overlay; the action,
     * if any, is forwarded to [player]. Ignored once exiting and while a line is captured (#86).
     */
    fun onAction(action: PlayerAction?) {
        if (exiting || capture.captured.value != null) return
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

    /**
     * An assistant key was pressed (#86): [AssistantAction.CaptureLine] pauses on the line just
     * spoken (or shows why it cannot), [AssistantAction.ReplayFragment] replays it -- pressed again
     * inside [LineCaptureController.REPLAY_BACK_WINDOW_MS] each press replays one more line back
     * (#339) -- [AssistantAction.TranslateLine] shows it in Spanish without saying it (#288),
     * [AssistantAction.ExplainLine] explains it without saying it (#293),
     * [AssistantAction.DismissOverlay] closes the overlay, resets the speech and resumes the movie,
     * and [AssistantAction.Consumed] does nothing. Ignored once exiting.
     */
    fun onAssistantAction(action: AssistantAction) {
        if (exiting) return
        when (action) {
            AssistantAction.CaptureLine -> captureLine()
            AssistantAction.ReplayFragment -> capture.replay()
            AssistantAction.TranslateLine -> showSpanish()
            AssistantAction.ExplainLine -> explainLine()
            AssistantAction.DismissOverlay -> dismissOverlay()
            AssistantAction.Consumed -> Unit
        }
    }

    /**
     * RIGHT (#293): asks [explanations] to explain the captured line, never spoken. The aligned
     * Spanish line goes with it -- LEFT's when LEFT already found one, else looked up here -- and the
     * panel reads "Pensando…" from the key press on. While a request is on its way or once the line
     * is explained another RIGHT changes nothing; after a failure it asks again.
     */
    private fun explainLine() {
        val controller = explanations ?: return
        val line = capture.captured.value ?: return
        if (local.value.explainGathering) return
        when (controller.state.value) {
            is ExplanationUiState.Thinking, is ExplanationUiState.Shown -> return
            ExplanationUiState.Idle, is ExplanationUiState.Unavailable -> Unit
        }
        val known = (local.value.spanish as? SpanishAnswer.Aligned)?.line?.text
        val opened = movie
        val title = local.value.title
        local.update { it.copy(explainGathering = true) }
        explainJob =
            viewModelScope.launch {
                val spanish = known ?: opened?.let { (id, file) -> spanishLines.lineFor(id, file, line.cue) }?.text
                // Dismissed (or another line captured) while gathering: this request is stale.
                if (capture.captured.value != line || !local.value.explainGathering) return@launch
                val track = hidden.track ?: SubtitleTrack(emptyList())
                controller.explain(ExplanationContext.of(track, line.cue, title, spanish))
                local.update { it.copy(explainGathering = false) }
            }
    }

    private fun clearExplanation() {
        explainJob?.cancel()
        explainJob = null
        local.update { it.copy(explainGathering = false) }
        explanations?.dismiss()
    }

    /**
     * LEFT (#288): the aligned Spanish subtitle line when [spanishLines] has one, else [speech]'s
     * translation, never spoken. A second LEFT while looking or once aligned changes nothing; once
     * on the bridge it asks [speech] again, which only retries a failed translation.
     */
    private fun showSpanish() {
        val line = capture.captured.value ?: return
        when (local.value.spanish) {
            SpanishAnswer.Looking, is SpanishAnswer.Aligned -> return
            SpanishAnswer.Bridge -> return speech.translate(line)
            null -> Unit
        }
        val opened = movie
        local.update { it.copy(spanish = SpanishAnswer.Looking) }
        spanishJob =
            viewModelScope.launch {
                val aligned = opened?.let { (id, file) -> spanishLines.lineFor(id, file, line.cue) }
                // Dismissed (or another line captured) while looking: this answer is stale.
                if (capture.captured.value != line || local.value.spanish != SpanishAnswer.Looking) return@launch
                if (aligned != null) {
                    local.update { it.copy(spanish = SpanishAnswer.Aligned(aligned)) }
                } else {
                    speech.translate(line)
                    local.update { it.copy(spanish = SpanishAnswer.Bridge) }
                }
            }
    }

    private fun clearSpanish() {
        spanishJob?.cancel()
        spanishJob = null
        local.update { it.copy(spanish = null) }
    }

    private fun dismissOverlay() {
        clearSpanish()
        clearExplanation()
        speech.reset()
        capture.dismiss()
    }

    private fun captureLine() {
        if (capture.captured.value != null) return
        if (!local.value.assistantAvailable) {
            // Nothing to read the line from: say so and leave the movie running.
            showMessage(NO_SUBTITLES)
            return
        }
        val wasPlaying = player.state.value == PlayerState.Playing
        when (capture.capture()) {
            CaptureResult.Captured -> {
                hideJob?.cancel()
                messageJob?.cancel()
                local.update { it.copy(overlayVisible = false, message = null) }
            }

            CaptureResult.NoLine -> {
                // capture() paused the movie; give it back as it was.
                if (wasPlaying) player.play()
                showMessage(NO_LINE)
            }
        }
    }

    /** BACK: closes the track panel without changing anything if it is open, otherwise [exit]s. */
    fun back() {
        val now = local.value
        if (capture.captured.value != null) {
            if (!exiting) dismissOverlay()
            return
        }
        if (now.tracksPanelOpen && errorOf(now, player.state.value) == null) closeTracks() else exit()
    }

    /** Makes audio track [id] the active one and closes the panel; the session persists it. */
    fun selectAudio(id: String) {
        if (exiting) return
        player.selectAudio(id)
        closeTracks()
    }

    /**
     * Makes subtitle track [id] the active one (null = `Desactivados`) and closes the panel. It goes
     * through [HiddenSubtitleController.selectByViewer], so hidden EN mode does not revert the
     * viewer's own choice (#227).
     */
    fun selectSubtitle(id: String?) {
        if (exiting) return
        hidden.selectByViewer(id)
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
        messageJob?.cancel()
        closeTracks()
        stopAssistant()
        viewModelScope.launch {
            if (local.value.streaming && openJob?.isCompleted == false) {
                // Still waiting for the first ranges (#226), which may take long: stop waiting.
                // Nothing was opened for close() to save; stop the controller and the player in
                // case the wait was cancelled just after opening the file.
                openJob?.cancelAndJoin()
                streamingController?.stop()
                player.release()
            } else {
                // Let a pending open finish so close() sees the item it has to save.
                openJob?.join()
            }
            session.close()
            closed = true
            local.update { it.copy(exited = true) }
        }
    }

    private fun streamStatusOf(state: StreamState): String? =
        when (state) {
            is StreamState.Preparing -> "$PREPARING_PREFIX${percentOf(state.readyBytes, state.requiredBytes)}"
            is StreamState.Buffering -> "$BUFFERING_PREFIX${percentOf(state.readyBytes, state.resumeAtBytes)}"
            StreamState.Idle, StreamState.Streaming, is StreamState.Failed -> null
        }

    private fun percentOf(
        part: Long,
        whole: Long,
    ): String = Formatters.percent(if (whole > 0) (part.coerceIn(0L, whole) * 100.0 / whole) else 0.0)

    private fun errorOf(
        local: Local,
        state: PlayerState,
    ): String? = local.error ?: (state as? PlayerState.Error)?.let { "$PLAYBACK_ERROR_PREFIX${it.message}" }

    /** Leaves the assistant as it was before this movie: no capture, hidden mode off. */
    private fun stopAssistant() {
        clearSpanish()
        clearExplanation()
        speech.reset()
        capture.dismiss(resume = false)
        hidden.stop()
    }

    private fun showMessage(text: String) {
        messageJob?.cancel()
        local.update { it.copy(message = text) }
        messageJob =
            viewModelScope.launch {
                delay(MESSAGE_TIMEOUT_MS)
                local.update { it.copy(message = null) }
            }
    }

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
        if (!exiting) stopAssistant()
        // viewModelScope is cancelled now, and with it a streaming open still waiting for ranges
        // (#226): stop the controller and the player it may just have opened.
        val streamingOpenCut = local.value.streaming && openJob?.isCompleted == false
        if (!closed) {
            closeScope?.launch {
                if (streamingOpenCut) {
                    streamingController?.stop()
                    player.release()
                }
                session.close()
            }
        }
    }

    /**
     * Builds the ViewModel from `AppContainer`'s bindings (ADR-0003 rule 1). The session runs on
     * its own main-thread scope, which also closes it if the ViewModel is cleared without an Exit.
     */
    class Factory(
        private val player: Player,
        private val repo: TorrentRepository,
        private val hidden: HiddenSubtitleController,
        private val capture: LineCaptureController,
        private val speech: AssistantSpeechController,
        private val streamingController: StreamingPlaybackController? = null,
        private val clock: () -> Long = System::currentTimeMillis,
        private val spanishLines: AlignedSpanishSource = AlignedSpanishSource.NONE,
        private val explanations: ExplanationController? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PlayerViewModel::class.java)) {
                "Unknown ViewModel class ${modelClass.name}"
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            return PlayerViewModel(
                PlaybackSession(player, repo, scope, clock),
                player,
                hidden,
                capture,
                speech,
                scope,
                streamingController = streamingController,
                repo = repo,
                spanishLines = spanishLines,
                explanations = explanations,
            ) as T
        }
    }

    companion object {
        /** How long the transport overlay stays up after the last key. */
        const val OVERLAY_TIMEOUT_MS = 4_000L

        const val FILE_MISSING = "El archivo no está disponible (¿se ha desconectado el disco?)"
        const val NOT_FOUND = "Esta película ya no está en la biblioteca"
        const val STREAMING_FAILED = "No se puede reproducir mientras se descarga"
        const val PLAYBACK_ERROR_PREFIX = "No se puede reproducir: "

        /** Streaming status (#226), followed by how much of what it waits for is on disk. */
        const val PREPARING_PREFIX = "Preparando la reproducción… "
        const val BUFFERING_PREFIX = "Cargando… "

        /** How long an assistant message stays in the transport overlay. */
        const val MESSAGE_TIMEOUT_MS = 3_000L

        const val NO_SUBTITLES = "Esta película no tiene subtítulos en inglés"
        const val NO_LINE = "No hay ninguna frase que capturar"

        /** Where LEFT's Spanish line came from (#288, ADR-0005 §5/§6). */
        const val LABEL_SUBTITLE = "subtítulo"
        const val LABEL_SUBTITLE_LATINO = "subtítulo · latino"
        const val LABEL_AI = "IA"
    }
}
