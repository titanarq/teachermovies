package com.teachermovies.discovery.fake

import com.teachermovies.discovery.client.DiscoveredTv
import com.teachermovies.discovery.client.ServiceDiscoverer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * [ServiceDiscoverer] for tests and for wiring without NSD (ADR-0003), backed by a
 * [MutableStateFlow]: [add] and [remove] control the visible TVs, which [discover] emits
 * deduplicated by instance name and sorted by it, whatever the service type. The service types
 * asked for are recorded in [requestedServiceTypes].
 */
class FakeServiceDiscoverer(initial: List<DiscoveredTv> = emptyList()) : ServiceDiscoverer {
    private val _tvs = MutableStateFlow(normalize(initial))
    private val _requested = mutableListOf<String>()

    /** The TVs currently visible. */
    val tvs: List<DiscoveredTv>
        get() = _tvs.value

    /** Every service type passed to [discover], in order. */
    val requestedServiceTypes: List<String>
        get() = synchronized(_requested) { _requested.toList() }

    override fun discover(serviceType: String): Flow<List<DiscoveredTv>> {
        synchronized(_requested) { _requested += serviceType }
        return _tvs.asStateFlow()
    }

    /** Makes [tv] visible, replacing any visible TV with the same instance name. */
    fun add(tv: DiscoveredTv) {
        _tvs.update { current -> normalize(current + tv) }
    }

    /** Removes the TV named [instanceName], if it is visible. */
    fun remove(instanceName: String) {
        _tvs.update { current -> current.filter { it.instanceName != instanceName } }
    }

    /** Removes every TV. */
    fun clear() {
        _tvs.value = emptyList()
    }

    private companion object {
        // associateBy keeps the last entry per key, so a later TV replaces an earlier one.
        fun normalize(tvs: List<DiscoveredTv>): List<DiscoveredTv> =
            tvs.associateBy { it.instanceName }.values.sortedBy { it.instanceName }
    }
}
