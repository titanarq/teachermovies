package com.teachermovies.discovery

/**
 * The platform seam under [NsdServiceAnnouncer]: one registration at a time. Implementations may
 * throw; the announcer turns every exception into [AnnouncementState.Failed].
 */
interface NsdRegistrar {
    /** Registers [info] and reports the outcome, possibly asynchronously, to [callback]. */
    fun register(info: TvServiceInfo, callback: RegistrationCallback)

    /** Withdraws the registration made by the last [register] call. */
    fun unregister()
}

/** Outcome of an [NsdRegistrar] registration. */
interface RegistrationCallback {
    /** The service is registered under [registeredName], which mDNS may have renamed. */
    fun onRegistered(registeredName: String)

    /** Registering or unregistering failed, for the short [reason]. */
    fun onFailed(reason: String)

    /** The registration has been withdrawn. */
    fun onUnregistered()
}
