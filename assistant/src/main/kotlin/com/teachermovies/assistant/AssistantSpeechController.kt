package com.teachermovies.assistant

import com.teachermovies.assistant.speech.Speaker
import com.teachermovies.assistant.speech.SpeakerAvailability
import com.teachermovies.assistant.speech.SpeakerState
import com.teachermovies.assistant.speech.SpeechLanguage
import com.teachermovies.assistant.translation.TranslationProvider
import com.teachermovies.assistant.translation.TranslationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

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
 * saying something; [speechAvailable] is the set of languages that can still be spoken (every
 * [SpeechLanguage] until [AssistantSpeechController.prepare] finds otherwise). A language missing
 * from it degrades to text on screen, independently of the other one.
 */
data class AssistantSpeechState(
    val speaking: Boolean = false,
    val translation: TranslationUiState = TranslationUiState.Idle,
    val speechAvailable: Set<SpeechLanguage> = SpeechLanguage.entries.toSet(),
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

    /** The line [TranslationUiState] belongs to, and the only translation coroutine, if any. */
    private var translatedLine: CapturedLine? = null
    private var translationJob: Job? = null

    /** Set by [translateAndSpeak]: say the translation in Spanish as soon as it is `Ready`. */
    private var speakWhenReady = false

    /**
     * Initialises the speaker once and sets [AssistantSpeechState.speechAvailable]:
     * [SpeakerAvailability.Ready] -> every language, [SpeakerAvailability.MissingVoice] -> every
     * language except the missing ones, [SpeakerAvailability.EngineUnavailable] (or a throwing
     * engine) -> none. Never throws (cancellation aside); later calls return without touching the engine.
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
            val available = availability.availableLanguages()
            mutableState.update { it.copy(speechAvailable = available) }
        }
    }

    /** Says the captured line again in English; returns what [Speaker.speak] returned. */
    fun speakOriginal(line: CapturedLine): Boolean = say(line.cue.text, SpeechLanguage.EN)

    /**
     * Translates [line] into Spanish on [scope]: [TranslationUiState.Loading], then `Ready` or
     * `Failed`. The same line while `Loading` or `Ready` does not reach the provider again; a
     * different line cancels the in-flight translation and starts over. Never throws.
     */
    fun translate(line: CapturedLine) {
        request(line, speak = false)
    }

    private fun request(
        line: CapturedLine,
        speak: Boolean,
    ) {
        if (line == translatedLine) {
            when (mutableState.value.translation) {
                is TranslationUiState.Ready -> {
                    if (speak) speakTranslation()
                    return
                }

                TranslationUiState.Loading -> {
                    if (speak) speakWhenReady = true
                    return
                }

                // Idle or Failed: a later attempt may succeed (Offline), so ask again.
                else -> {
                    Unit
                }
            }
        }
        translationJob?.cancel()
        translatedLine = line
        // Set before launching: an immediate dispatcher may finish the coroutine right away.
        speakWhenReady = speak
        mutableState.update { it.copy(translation = TranslationUiState.Loading) }
        translationJob =
            scope.launch {
                val result =
                    try {
                        translations.translate(line.cue.text)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The provider contract forbids this; treat a misbehaving one as refusing.
                        TranslationResult.Unavailable(e.javaClass.simpleName)
                    }
                // A cancelled call that returned without suspending must not overwrite the new line.
                if (!isActive || translatedLine != line) return@launch
                val uiState = result.toUiState()
                mutableState.update { it.copy(translation = uiState) }
                if (speakWhenReady) {
                    speakWhenReady = false
                    if (uiState is TranslationUiState.Ready) say(uiState.text, SpeechLanguage.ES)
                }
            }
    }

    /**
     * Says the `Ready` translation in Spanish and returns what [Speaker.speak] returned; `false`
     * and nothing else happens unless [AssistantSpeechState.translation] is `Ready`.
     */
    fun speakTranslation(): Boolean {
        val translation = mutableState.value.translation
        if (translation !is TranslationUiState.Ready) return false
        return say(translation.text, SpeechLanguage.ES)
    }

    /**
     * [translate]s [line] (reusing a `Ready` result for the same line) and says the result in
     * Spanish as soon as it arrives. On `Failed` nothing is said and the failure stays in the
     * state for the screen to show; without speech only the text is shown.
     */
    fun translateAndSpeak(line: CapturedLine) {
        request(line, speak = true)
    }

    /**
     * Silences the speaker, cancels every coroutine this controller started and returns the state
     * to its defaults, keeping [AssistantSpeechState.speechAvailable].
     */
    fun reset() {
        speaker.stop()
        speakingMirror?.cancel()
        speakingMirror = null
        translationJob?.cancel()
        translationJob = null
        translatedLine = null
        speakWhenReady = false
        mutableState.update { AssistantSpeechState(speechAvailable = it.speechAvailable) }
    }

    private fun say(
        text: String,
        language: SpeechLanguage,
    ): Boolean {
        if (language !in mutableState.value.speechAvailable) return false
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

    private fun SpeakerAvailability.availableLanguages(): Set<SpeechLanguage> =
        when (this) {
            SpeakerAvailability.Ready -> SpeechLanguage.entries.toSet()
            is SpeakerAvailability.MissingVoice -> SpeechLanguage.entries.toSet() - languages
            SpeakerAvailability.EngineUnavailable -> emptySet()
        }

    private fun TranslationResult.toUiState(): TranslationUiState =
        when (this) {
            is TranslationResult.Translated -> TranslationUiState.Ready(text)
            TranslationResult.Offline -> TranslationUiState.Failed(TranslationFailure.OFFLINE)
            is TranslationResult.Unavailable -> TranslationUiState.Failed(TranslationFailure.UNAVAILABLE)
        }
}
