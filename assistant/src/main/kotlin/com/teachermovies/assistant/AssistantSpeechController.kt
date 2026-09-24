package com.teachermovies.assistant

import com.teachermovies.assistant.speech.Speaker
import com.teachermovies.assistant.speech.SpeakerAvailability
import com.teachermovies.assistant.speech.SpeakerState
import com.teachermovies.assistant.speech.SpeechLanguage
import com.teachermovies.assistant.translation.TranslationProvider
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why a translation could not be shown. */
enum class TranslationFailure {
    /** A provider is configured but cannot be reached right now; a later attempt may succeed. */
    OFFLINE,

    /** No provider is configured, or it refused the line. */
    UNAVAILABLE,
}

/** The Spanish translation of the captured line, as the player screen renders it. */
sealed interface TranslationUiState {
    /** Nothing has been asked for. */
    data object Idle : TranslationUiState

    /** The provider is working on it. */
    data object Loading : TranslationUiState

    /** [text] is the Spanish translation. */
    data class Ready(
        val text: String,
    ) : TranslationUiState

    /** The line could not be translated; the screen shows why. */
    data class Failed(
        val reason: TranslationFailure,
    ) : TranslationUiState
}

/**
 * What [AssistantSpeechController] exposes to the player screen. [speaking] mirrors the speaker
 * saying something; [speechAvailable] is `false` once [AssistantSpeechController.prepare] found
 * no engine or a missing voice, and then the assistant degrades to text on screen.
 */
data class AssistantSpeechState(
    val speaking: Boolean = false,
    val translation: TranslationUiState = TranslationUiState.Idle,
    val speechAvailable: Boolean = true,
)

/**
 * Turns a [CapturedLine] into the two spoken assistant answers (VISION §7): say the line again in
 * English, and show plus say its Spanish translation. Works only through [Speaker] and
 * [TranslationProvider]; no Android, `TextToSpeech` or libVLC type is referenced here.
 *
 * Coroutines are started on [scope] only while there is something to follow: the mirror of
 * [Speaker.state] after an accepted utterance, and at most one translation. [reset] cancels both.
 */
class AssistantSpeechController(
    private val speaker: Speaker,
    private val translations: TranslationProvider,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(AssistantSpeechState())

    val state: StateFlow<AssistantSpeechState> = mutableState.asStateFlow()

    private val prepareMutex = Mutex()
    private var prepared = false
    private var speakingMirror: Job? = null

    /**
     * Initialises the speaker once. [SpeakerAvailability.EngineUnavailable] or
     * [SpeakerAvailability.MissingVoice] sets [AssistantSpeechState.speechAvailable] to `false`.
     * Never throws (cancellation aside); later calls return without touching the engine.
     */
    suspend fun prepare() {
        prepareMutex.withLock {
            if (prepared) return
            val availability =
                try {
                    speaker.prepare()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A misbehaving engine is the same as no engine: degrade to text only.
                    SpeakerAvailability.EngineUnavailable
                }
            prepared = true
            mutableState.update { it.copy(speechAvailable = availability == SpeakerAvailability.Ready) }
        }
    }

    /** Says the captured line again in English; returns what [Speaker.speak] returned. */
    fun speakOriginal(line: CapturedLine): Boolean = say(line.cue.text, SpeechLanguage.EN)

    /**
     * Silences the speaker, cancels every coroutine this controller started and returns the state
     * to its defaults, keeping [AssistantSpeechState.speechAvailable].
     */
    fun reset() {
        speaker.stop()
        speakingMirror?.cancel()
        speakingMirror = null
        mutableState.update { AssistantSpeechState(speechAvailable = it.speechAvailable) }
    }

    private fun say(
        text: String,
        language: SpeechLanguage,
    ): Boolean {
        if (!mutableState.value.speechAvailable) return false
        val accepted = speaker.speak(text, language)
        if (accepted) followSpeaker()
        return accepted
    }

    /** Mirrors [Speaker.state] into [AssistantSpeechState.speaking] until [reset]. */
    private fun followSpeaker() {
        mutableState.update { it.copy(speaking = speaker.state.value is SpeakerState.Speaking) }
        if (speakingMirror?.isActive == true) return
        speakingMirror =
            scope.launch {
                speaker.state.collect { s ->
                    mutableState.update { it.copy(speaking = s is SpeakerState.Speaking) }
                }
            }
    }
}
