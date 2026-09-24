package com.teachermovies.discovery.fake

import com.teachermovies.discovery.AnnouncementState
import com.teachermovies.discovery.ServiceAnnouncer
import com.teachermovies.discovery.TvServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [ServiceAnnouncer] for tests and for wiring without NSD (ADR-0003). Records every call in
 * [calls], in order. [announce] moves to [AnnouncementState.Announcing] and [stop] to
 * [AnnouncementState.Idle]; everything else is driven by the test through [emitAnnounced],
 * [emitFailed] and [setState].
 */
class FakeServiceAnnouncer : ServiceAnnouncer {
    sealed interface Call {
        data class Announce(val info: TvServiceInfo) : Call

        data object Stop : Call
    }

    private val _state = MutableStateFlow<AnnouncementState>(AnnouncementState.Idle)
    private val _calls = mutableListOf<Call>()

    override val state: StateFlow<AnnouncementState> = _state.asStateFlow()

    /** Every [announce] and [stop] call so far, in order. */
    val calls: List<Call>
        get() = synchronized(_calls) { _calls.toList() }

    /** The info of the last [announce] call, or null if there was none. */
    val lastAnnounced: TvServiceInfo?
        get() = calls.filterIsInstance<Call.Announce>().lastOrNull()?.info

    override fun announce(info: TvServiceInfo) {
        synchronized(_calls) { _calls += Call.Announce(info) }
        _state.value = AnnouncementState.Announcing(info)
    }

    override fun stop() {
        synchronized(_calls) { _calls += Call.Stop }
        _state.value = AnnouncementState.Idle
    }

    /** Completes the last announcement as registered under [registeredName]. */
    fun emitAnnounced(registeredName: String? = null) {
        val info = checkNotNull(lastAnnounced) { "announce() was never called" }
        _state.value = AnnouncementState.Announced(info, registeredName ?: info.instanceName)
    }

    /** Fails the last announcement with [reason]. */
    fun emitFailed(reason: String) {
        val info = checkNotNull(lastAnnounced) { "announce() was never called" }
        _state.value = AnnouncementState.Failed(info, reason)
    }

    /** Sets [state] to exactly [state]. */
    fun setState(state: AnnouncementState) {
        _state.value = state
    }

    /** Forgets the recorded calls; [state] is left as it is. */
    fun clearCalls() {
        synchronized(_calls) { _calls.clear() }
    }
}
