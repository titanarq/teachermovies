package com.teachermovies.discovery.client

import kotlinx.coroutines.flow.Flow

/** Browses the LAN for the TV service. */
interface ServiceDiscoverer {
    /**
     * The TVs currently visible for [serviceType]. Each emission is the complete current set,
     * deduplicated by [DiscoveredTv.instanceName] (the last resolution wins) and sorted by it. A
     * new emission follows every appearance, re-resolution or disappearance; a service that has
     * been found but not resolved yet is not emitted. Failures never end the flow: only the
     * collector cancelling does.
     */
    fun discover(serviceType: String = "_http._tcp"): Flow<List<DiscoveredTv>>
}
