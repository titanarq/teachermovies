package com.teachermovies.discovery

/** Where the LAN announcement of the TV's HTTP service stands. */
sealed interface AnnouncementState {
    /** Nothing is announced. */
    data object Idle : AnnouncementState

    /** A registration for [info] has been requested and not yet confirmed. */
    data class Announcing(
        val info: TvServiceInfo,
    ) : AnnouncementState

    /**
     * [info] is announced. mDNS may rename an instance on a name conflict, so [registeredName]
     * is the name actually registered, which can differ from [TvServiceInfo.instanceName].
     */
    data class Announced(
        val info: TvServiceInfo,
        val registeredName: String,
    ) : AnnouncementState

    /** Announcing [info] failed, for the short human-readable [reason]. */
    data class Failed(
        val info: TvServiceInfo,
        val reason: String,
    ) : AnnouncementState
}
