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

    /** The engine is up but has no voice for [language]; the feature degrades to text only. */
    data class MissingVoice(
        val language: SpeechLanguage,
    ) : SpeakerAvailability

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
 * - [speak] returns `false` and leaves [state] at [SpeakerState.Idle] unless [prepare] returned
 *   [SpeakerAvailability.Ready] (so never for the language a [SpeakerAvailability.MissingVoice]
 *   reports), and for blank or otherwise unspeakable text. It never throws.
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
