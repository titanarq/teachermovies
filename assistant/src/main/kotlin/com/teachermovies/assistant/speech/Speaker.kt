package com.teachermovies.assistant.speech

import kotlinx.coroutines.flow.StateFlow

/** A language the assistant can speak, with the BCP-47 tag handed to the speech engine. */
enum class SpeechLanguage(
    val tag: String,
) {
    EN("en-US"),
    ES("es-ES"),
}

/** What a [Speaker] is doing right now. */
sealed interface SpeakerState {
    /** Nothing is being said. */
    data object Idle : SpeakerState

    /** [text] is being said in [language]. */
    data class Speaking(
        val text: String,
        val language: SpeechLanguage,
    ) : SpeakerState
}

/** Outcome of [Speaker.prepare]: whether the speech engine can say anything at all. */
sealed interface SpeakerAvailability {
    /** The engine is up and has a voice for every [SpeechLanguage]. */
    data object Ready : SpeakerAvailability

    /**
     * The engine is up but has no voice for [languages] (never empty); only those languages
     * degrade to text only, every other [SpeechLanguage] is still spoken. When every voice is
     * missing this is `MissingVoice(setOf(EN, ES))`, not [EngineUnavailable].
     */
    data class MissingVoice(
        val languages: Set<SpeechLanguage>,
    ) : SpeakerAvailability {
        init {
            require(languages.isNotEmpty()) { "MissingVoice needs at least one language" }
        }
    }

    /** No speech engine on the device, it failed to initialise, or it did not answer in time. */
    data object EngineUnavailable : SpeakerAvailability
}

/**
 * Says a sentence out loud. Callers above `:assistant` compile against this interface only; no
 * Android or `android.speech.tts` type appears in it. Tests use
 * [com.teachermovies.assistant.speech.fake.FakeSpeaker].
 *
 * Contract:
 * - [availability] is `null` until [prepare] has run once, then holds its last result.
 * - [speak] accepts a language when [prepare] returned [SpeakerAvailability.Ready], or returned
 *   [SpeakerAvailability.MissingVoice] whose `languages` does not contain it
 *   ([SpeechRequests.allows]). It returns `false` and leaves [state] at [SpeakerState.Idle] for a
 *   language `MissingVoice` reports, for [SpeakerAvailability.EngineUnavailable], before
 *   [prepare], and for blank or otherwise unspeakable text. It never throws.
 * - A new [speak] replaces whatever is being said (flush, not queue).
 * - [stop] silences the engine and sets [state] to [SpeakerState.Idle].
 * - [shutdown] releases the engine; it is idempotent and safe before [prepare]. After it,
 *   [speak] returns `false`.
 */
interface Speaker {
    val state: StateFlow<SpeakerState>

    val availability: StateFlow<SpeakerAvailability?>

    suspend fun prepare(): SpeakerAvailability

    fun speak(
        text: String,
        language: SpeechLanguage,
    ): Boolean

    fun stop()

    fun shutdown()
}
