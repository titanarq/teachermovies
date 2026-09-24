package com.teachermovies.discovery.client

/**
 * The seam that keeps the platform's service discovery out of the unit tests. [start] begins
 * browsing for a service type and resolving every service found, reporting through the callback;
 * [stop] ends the browse started last. Callbacks may arrive on any thread.
 */
interface NsdBrowser {
    fun start(serviceType: String, callback: BrowseCallback)

    fun stop()
}

/** What an [NsdBrowser] reports while browsing. */
interface BrowseCallback {
    /** A service named [instanceName] appeared; it is not usable until [onResolved]. */
    fun onFound(instanceName: String)

    /** The service named [instanceName] disappeared. */
    fun onLost(instanceName: String)

    /** A found service was resolved to [tv] (again, if it had been resolved before). */
    fun onResolved(tv: DiscoveredTv)

    /** Browsing or resolving failed; [reason] is a short human-readable message. */
    fun onFailed(reason: String)
}
