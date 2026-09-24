package com.teachermovies.discovery

import kotlinx.coroutines.flow.StateFlow

/**
 * Announces the TV's HTTP service on the LAN. Neither method throws: every failure is reported
 * as [AnnouncementState.Failed] on [state]. A failed announcement never blocks the HTTP server;
 * the IP:port the TV shows stays the fallback.
 */
interface ServiceAnnouncer {
    val state: StateFlow<AnnouncementState>

    /** Announces [info], replacing any announcement already in place. */
    fun announce(info: TvServiceInfo)

    /** Withdraws the current announcement, if any; a no-op while [AnnouncementState.Idle]. */
    fun stop()
}
