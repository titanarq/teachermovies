package com.teachermovies.discovery.client

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress

/**
 * [NsdBrowser] over Android's [NsdManager]: the only client file that touches `android.net.nsd`.
 * Browses with `discoverServices` (DNS-SD) and resolves every found service with
 * `resolveService`, one at a time (older platforms reject concurrent resolutions). A resolution
 * that completes after its service was lost, or after [stop], is dropped. Platform errors are
 * reported through [BrowseCallback.onFailed]; neither method throws.
 */
class AndroidNsdBrowser(context: Context) : NsdBrowser {
    private val nsdManager: NsdManager by lazy {
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val lock = Any()
    private var session: Session? = null

    override fun start(serviceType: String, callback: BrowseCallback) {
        stop()
        val newSession = Session(callback)
        synchronized(lock) { session = newSession }
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, newSession)
        } catch (e: Exception) {
            synchronized(lock) { if (session === newSession) session = null }
            callback.onFailed("NSD discovery could not start: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun stop() {
        val active = synchronized(lock) { session.also { session = null } } ?: return
        active.close()
        try {
            nsdManager.stopServiceDiscovery(active)
        } catch (e: Exception) {
            // The discovery never started (or already stopped); report it to the old session.
            active.callback.onFailed("NSD discovery could not stop: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private inner class Session(val callback: BrowseCallback) : NsdManager.DiscoveryListener {
        // Guarded by `this`.
        private var closed = false
        private val found = mutableMapOf<String, NsdServiceInfo>()
        private val queue = ArrayDeque<String>()
        private var resolving: String? = null

        fun close() {
            synchronized(this) {
                closed = true
                found.clear()
                queue.clear()
            }
        }

        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            synchronized(lock) { if (session === this) session = null }
            close()
            callback.onFailed("NSD discovery failed to start (error $errorCode)")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            callback.onFailed("NSD discovery failed to stop (error $errorCode)")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            val name = serviceInfo.serviceName
            synchronized(this) {
                if (closed) return
                found[name] = serviceInfo
                if (name != resolving && name !in queue) queue.addLast(name)
            }
            callback.onFound(name)
            resolveNext()
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            val name = serviceInfo.serviceName
            synchronized(this) {
                if (closed) return
                found.remove(name)
                queue.remove(name)
            }
            callback.onLost(name)
        }

        @Suppress("DEPRECATION") // resolveService is the only resolution API below API 34.
        private fun resolveNext() {
            val info =
                synchronized(this) {
                    if (closed || resolving != null) return
                    val name = queue.removeFirstOrNull() ?: return
                    resolving = name
                    found.getValue(name)
                }
            try {
                nsdManager.resolveService(info, ResolveListener(info.serviceName))
            } catch (e: Exception) {
                finishResolving()
                callback.onFailed("NSD resolution could not start: ${e.message ?: e.javaClass.simpleName}")
                resolveNext()
            }
        }

        private fun finishResolving(): Boolean =
            synchronized(this) {
                val name = resolving
                val stillWanted = !closed && name != null && name in found
                resolving = null
                stillWanted
            }

        private inner class ResolveListener(private val name: String) : NsdManager.ResolveListener {
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val stillWanted = finishResolving()
                val tv = serviceInfo.toDiscoveredTv(name)
                when {
                    !stillWanted -> Unit
                    tv == null -> callback.onFailed("NSD resolved $name without an address")
                    else -> callback.onResolved(tv)
                }
                resolveNext()
            }

            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (finishResolving()) callback.onFailed("NSD resolution of $name failed (error $errorCode)")
                resolveNext()
            }
        }
    }

    private companion object {
        fun NsdServiceInfo.toDiscoveredTv(name: String): DiscoveredTv? {
            val address = preferredAddress() ?: return null
            val host = address.hostAddress ?: return null
            val txt =
                attributes.orEmpty().mapValues { (_, value) -> value?.toString(Charsets.UTF_8).orEmpty() }
            return DiscoveredTv(instanceName = name, host = host, port = port, attributes = txt)
        }

        /** An IPv4 address when there is one: it drops straight into `http://host:port`. */
        @Suppress("DEPRECATION") // getHost is the only address API below API 34.
        fun NsdServiceInfo.preferredAddress(): InetAddress? {
            val addresses =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    hostAddresses
                } else {
                    listOfNotNull(host)
                }
            return addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
        }
    }
}
