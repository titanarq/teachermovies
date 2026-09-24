package com.teachermovies.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [Speaker] over Android's [TextToSpeech] (ADR-0001 §6). Not JVM-testable: every decidable rule
 * lives in [SpeechRequests] and is mirrored by [com.teachermovies.assistant.speech.fake.FakeSpeaker].
 *
 * The engine is created by [prepare], which waits up to [timeoutMs] for its `OnInitListener`.
 * Engine callbacks arrive on binder threads; they are bridged onto [state] under [lock], and only
 * for the utterance most recently handed to the engine, so a flushed one never overwrites it.
 * Nothing here logs: the text being spoken is user content (AGENTS.md, Security).
 */
class AndroidTextToSpeechSpeaker(
    context: Context,
    private val timeoutMs: Long = 5_000,
) : Speaker {
    private val appContext: Context = context.applicationContext ?: context

    private val lock = Any()

    // Serialises concurrent `prepare` calls so at most one engine is ever created.
    private val prepareMutex = Mutex()
    private val mutableState = MutableStateFlow<SpeakerState>(SpeakerState.Idle)
    private val mutableAvailability = MutableStateFlow<SpeakerAvailability?>(null)

    override val state: StateFlow<SpeakerState> = mutableState.asStateFlow()
    override val availability: StateFlow<SpeakerAvailability?> = mutableAvailability.asStateFlow()

    private var engine: TextToSpeech? = null
    private var isShutDown = false
    private var nextSeq = 0L
    private var current: PendingUtterance? = null

    private class PendingUtterance(
        val id: String,
        val text: String,
        val language: SpeechLanguage,
    )

    override suspend fun prepare(): SpeakerAvailability = prepareMutex.withLock { prepareLocked() }

    private suspend fun prepareLocked(): SpeakerAvailability {
        synchronized(lock) {
            if (isShutDown) return publish(SpeakerAvailability.EngineUnavailable)
            val ready = engine
            if (ready != null) return publish(checkVoices(ready))
        }

        val initStatus = CompletableDeferred<Int>()
        // The constructor may call the listener synchronously (e.g. no engine installed), which is
        // why the deferred exists before it runs.
        val created =
            try {
                TextToSpeech(appContext) { status -> initStatus.complete(status) }
            } catch (e: RuntimeException) {
                return publish(SpeakerAvailability.EngineUnavailable)
            }

        val status = withTimeoutOrNull(timeoutMs) { initStatus.await() }
        if (status != TextToSpeech.SUCCESS) {
            release(created)
            return publish(SpeakerAvailability.EngineUnavailable)
        }

        synchronized(lock) {
            if (isShutDown) {
                release(created)
                return publish(SpeakerAvailability.EngineUnavailable)
            }
            created.setOnUtteranceProgressListener(ProgressBridge())
            engine = created
            return publish(checkVoices(created))
        }
    }

    override fun speak(
        text: String,
        language: SpeechLanguage,
    ): Boolean {
        if (!SpeechRequests.isSpeakable(text)) return false
        synchronized(lock) {
            val tts = engine ?: return false
            if (isShutDown || !SpeechRequests.allows(mutableAvailability.value, language)) return false
            val utterance = PendingUtterance(SpeechRequests.utteranceId(nextSeq++), text, language)
            val accepted =
                try {
                    val languageResult = tts.setLanguage(Locale.forLanguageTag(language.tag))
                    languageResult >= TextToSpeech.LANG_AVAILABLE &&
                        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utterance.id) == TextToSpeech.SUCCESS
                } catch (e: RuntimeException) {
                    // A misbehaving engine is reported as a refused request, never thrown.
                    false
                }
            if (!accepted) {
                current = null
                mutableState.value = SpeakerState.Idle
                return false
            }
            current = utterance
            return true
        }
    }

    override fun stop() {
        synchronized(lock) {
            current = null
            try {
                engine?.stop()
            } catch (e: RuntimeException) {
                // The engine died under us; there is nothing left to stop.
            }
            mutableState.value = SpeakerState.Idle
        }
    }

    override fun shutdown() {
        synchronized(lock) {
            if (isShutDown) return
            isShutDown = true
            current = null
            engine?.let(::release)
            engine = null
            mutableState.value = SpeakerState.Idle
        }
    }

    private fun publish(result: SpeakerAvailability): SpeakerAvailability {
        mutableAvailability.value = result
        return result
    }

    // Checks every language (no early return on the first missing one) so the result names the
    // full missing set and each language degrades independently.
    private fun checkVoices(tts: TextToSpeech): SpeakerAvailability {
        val missing = mutableSetOf<SpeechLanguage>()
        for (language in SpeechLanguage.entries) {
            val available =
                try {
                    tts.isLanguageAvailable(Locale.forLanguageTag(language.tag)) >= TextToSpeech.LANG_AVAILABLE
                } catch (e: RuntimeException) {
                    return SpeakerAvailability.EngineUnavailable
                }
            if (!available) missing += language
        }
        return if (missing.isEmpty()) SpeakerAvailability.Ready else SpeakerAvailability.MissingVoice(missing)
    }

    private fun release(tts: TextToSpeech) {
        try {
            tts.stop()
            tts.shutdown()
        } catch (e: RuntimeException) {
            // Releasing a dead engine: already gone, nothing else to free.
        }
    }

    private fun onStarted(utteranceId: String?) {
        synchronized(lock) {
            val utterance = current ?: return
            if (utterance.id != utteranceId) return
            mutableState.value = SpeakerState.Speaking(utterance.text, utterance.language)
        }
    }

    private fun onFinished(utteranceId: String?) {
        synchronized(lock) {
            if (current?.id != utteranceId) return
            current = null
            mutableState.value = SpeakerState.Idle
        }
    }

    private inner class ProgressBridge : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = onStarted(utteranceId)

        override fun onDone(utteranceId: String?) = onFinished(utteranceId)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = onFinished(utteranceId)

        override fun onError(
            utteranceId: String?,
            errorCode: Int,
        ) = onFinished(utteranceId)

        override fun onStop(
            utteranceId: String?,
            interrupted: Boolean,
        ) = onFinished(utteranceId)
    }
}
