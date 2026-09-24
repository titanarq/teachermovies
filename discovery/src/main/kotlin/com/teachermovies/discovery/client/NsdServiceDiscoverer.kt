package com.teachermovies.discovery.client

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * Production [ServiceDiscoverer] over an [NsdBrowser]. Each collection starts its own browse and
 * stops it exactly once when the collector cancels. Failures (a [BrowseCallback.onFailed], or an
 * exception thrown by [NsdBrowser.start]) leave the last emitted list in place and the flow open.
 */
class NsdServiceDiscoverer(private val browser: NsdBrowser) : ServiceDiscoverer {
    override fun discover(serviceType: String): Flow<List<DiscoveredTv>> =
        callbackFlow {
                val lock = Any()
                val resolved = mutableMapOf<String, DiscoveredTv>()

                // Called under the lock, so emissions keep the order of the callbacks.
                fun publish() {
                    trySend(resolved.values.sortedBy { it.instanceName })
                }

                val callback =
                    object : BrowseCallback {
                        override fun onFound(instanceName: String) {
                            // Not usable until resolved: nothing to emit yet.
                        }

                        override fun onLost(instanceName: String) {
                            synchronized(lock) {
                                if (resolved.remove(instanceName) != null) publish()
                            }
                        }

                        override fun onResolved(tv: DiscoveredTv) {
                            synchronized(lock) {
                                resolved[tv.instanceName] = tv
                                publish()
                            }
                        }

                        override fun onFailed(reason: String) {
                            // The last emitted list stays in place and the flow stays open.
                        }
                    }

                try {
                    browser.start(serviceType, callback)
                } catch (e: Exception) {
                    callback.onFailed(e.message ?: e.javaClass.simpleName)
                }
                awaitClose { browser.stop() }
            }
            .buffer(Channel.UNLIMITED)
}
