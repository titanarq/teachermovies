package com.teachermovies.discovery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [ServiceAnnouncer] over an [NsdRegistrar]. Holds the whole state machine; the registrar only
 * talks to the platform. Callbacks from a registration that has since been replaced or stopped
 * are ignored, so a late platform callback can never overwrite the current state.
 */
class NsdServiceAnnouncer(private val registrar: NsdRegistrar) : ServiceAnnouncer {
    private val lock = Any()
    private val _state = MutableStateFlow<AnnouncementState>(AnnouncementState.Idle)

    /** The registration whose callbacks are currently honoured; null when none is active. */
    private var current: Registration? = null

    override val state: StateFlow<AnnouncementState> = _state.asStateFlow()

    override fun announce(info: TvServiceInfo) {
        synchronized(lock) {
            if (current != null && !unregisterCurrent(info)) return
            if (info.port !in VALID_PORTS) {
                _state.value = AnnouncementState.Failed(info, "invalid port ${info.port}")
                return
            }
            val registration = Registration(info)
            current = registration
            _state.value = AnnouncementState.Announcing(info)
            try {
                registrar.register(info, registration)
            } catch (e: Exception) {
                if (current === registration) {
                    current = null
                    _state.value = AnnouncementState.Failed(info, reason("register", e))
                }
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            val registration = current
            if (registration == null) {
                _state.value = AnnouncementState.Idle
                return
            }
            if (unregisterCurrent(registration.info)) _state.value = AnnouncementState.Idle
        }
    }

    /**
     * Withdraws the active registration. Returns false, having set [AnnouncementState.Failed]
     * for [info], if the registrar threw.
     */
    private fun unregisterCurrent(info: TvServiceInfo): Boolean {
        current = null
        return try {
            registrar.unregister()
            true
        } catch (e: Exception) {
            _state.value = AnnouncementState.Failed(info, reason("unregister", e))
            false
        }
    }

    private inner class Registration(val info: TvServiceInfo) : RegistrationCallback {
        override fun onRegistered(registeredName: String) {
            synchronized(lock) {
                if (current !== this) return
                _state.value = AnnouncementState.Announced(info, registeredName)
            }
        }

        override fun onFailed(reason: String) {
            synchronized(lock) {
                if (current !== this) return
                current = null
                _state.value = AnnouncementState.Failed(info, reason)
            }
        }

        override fun onUnregistered() {
            synchronized(lock) {
                if (current !== this) return
                current = null
                _state.value = AnnouncementState.Idle
            }
        }
    }

    private companion object {
        val VALID_PORTS = 1..65535

        fun reason(action: String, e: Exception): String =
            "$action failed: ${e.message ?: e.javaClass.simpleName}"
    }
}
