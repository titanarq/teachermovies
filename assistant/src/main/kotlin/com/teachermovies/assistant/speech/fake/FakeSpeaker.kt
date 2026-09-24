package com.teachermovies.assistant.speech.fake

import com.teachermovies.assistant.speech.Speaker
import com.teachermovies.assistant.speech.SpeakerAvailability
import com.teachermovies.assistant.speech.SpeakerState
import com.teachermovies.assistant.speech.SpeechLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [Speaker] for every test above `:assistant` (ADR-0003: fakes live in the main source
 * set). No engine, no real time: [prepare] answers [nextAvailability], an accepted [speak] goes
 * straight to [SpeakerState.Speaking] and stays there until the test calls
 * [finishCurrentUtterance] or [failCurrentUtterance] (or [stop]).
 */
class FakeSpeaker : Speaker {
    /** What the next [prepare] returns. */
    var nextAvailability: SpeakerAvailability = SpeakerAvailability.Ready

    private val mutableState = MutableStateFlow<SpeakerState>(SpeakerState.Idle)
    private val mutableAvailability = MutableStateFlow<SpeakerAvailability?>(null)
    private val mutableSpoken = mutableListOf<Pair<String, SpeechLanguage>>()
    private var isShutDown = false

    override val state: StateFlow<SpeakerState> = mutableState.asStateFlow()
    override val availability: StateFlow<SpeakerAvailability?> = mutableAvailability.asStateFlow()

    /** Every sentence [speak] accepted, in order. */
    val spoken: List<Pair<String, SpeechLanguage>>
        get() = mutableSpoken.toList()

    /** How many times [shutdown] actually released the (fake) engine: 0 or 1. */
    var shutdownCount: Int = 0
        private set

    override suspend fun prepare(): SpeakerAvailability {
        val result = if (isShutDown) SpeakerAvailability.EngineUnavailable else nextAvailability
        mutableAvailability.value = result
        return result
    }

    override fun speak(
        text: String,
        language: SpeechLanguage,
    ): Boolean {
        if (isShutDown) return false
        if (mutableAvailability.value != SpeakerAvailability.Ready) return false
        if (text.isBlank()) return false
        mutableSpoken += text to language
        mutableState.value = SpeakerState.Speaking(text, language)
        return true
    }

    /** The engine finished the current utterance: `Speaking` -> `Idle`. */
    fun finishCurrentUtterance() {
        mutableState.value = SpeakerState.Idle
    }

    /** The engine failed mid-utterance: `Speaking` -> `Idle`. */
    fun failCurrentUtterance() {
        mutableState.value = SpeakerState.Idle
    }

    override fun stop() {
        mutableState.value = SpeakerState.Idle
    }

    override fun shutdown() {
        if (isShutDown) return
        isShutDown = true
        shutdownCount++
        mutableState.value = SpeakerState.Idle
    }
}
