package com.teachermovies.tv.di

import com.teachermovies.assistant.speech.SpeechLanguage
import com.teachermovies.assistant.translation.BridgeTranslateGateway
import com.teachermovies.assistant.translation.BridgeTranslateOutcome
import com.teachermovies.http.bridge.AssistantBridge
import com.teachermovies.http.bridge.BridgeJob
import com.teachermovies.http.bridge.BridgeOutcome
import java.time.Duration
import kotlin.time.toKotlinDuration

/**
 * The adapter `BridgeTranslateGateway` asks wiring for (#287, #288): a translation job on the job
 * hub [bridge] (#275), its outcome renamed member for member. The hub's translate job is always
 * English to Castilian Spanish (ADR-0005 §6), so [translate]'s languages are not sent.
 *
 * [BridgeOutcome.Replaced] -- a newer translation took the slot -- only reaches a caller that has
 * already moved on to the newer line, so it is [BridgeTranslateOutcome.Disconnected]: this job's
 * answer is not coming, and asking again may still get one. A bridge failure keeps its code, and a
 * job the hub refused to send (too long) its reason, as the reason, which never reaches the screen.
 */
class HubBridgeTranslateGateway(
    private val bridge: AssistantBridge,
) : BridgeTranslateGateway {
    override suspend fun translate(
        text: String,
        from: SpeechLanguage,
        to: SpeechLanguage,
        timeout: Duration,
    ): BridgeTranslateOutcome =
        when (val outcome = bridge.submit(BridgeJob.Translate(text), timeout.toKotlinDuration())) {
            is BridgeOutcome.Done -> BridgeTranslateOutcome.Done(outcome.text)
            BridgeOutcome.NoBridge -> BridgeTranslateOutcome.NoBridge
            BridgeOutcome.TimedOut -> BridgeTranslateOutcome.TimedOut
            BridgeOutcome.Disconnected, BridgeOutcome.Replaced -> BridgeTranslateOutcome.Disconnected
            is BridgeOutcome.BridgeError -> BridgeTranslateOutcome.BridgeError(outcome.code)
            is BridgeOutcome.Rejected -> BridgeTranslateOutcome.BridgeError(outcome.reason)
        }
}
