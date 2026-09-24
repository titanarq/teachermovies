package com.teachermovies.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * [NsdRegistrar] over Android's [NsdManager]: the only file in the module that touches
 * `android.net.nsd`. Exceptions from the platform propagate to [NsdServiceAnnouncer], which
 * turns them into [AnnouncementState.Failed].
 */
class AndroidNsdRegistrar(
    context: Context,
) : NsdRegistrar {
    private val nsdManager: NsdManager by lazy {
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val lock = Any()
    private var listener: NsdManager.RegistrationListener? = null

    override fun register(
        info: TvServiceInfo,
        callback: RegistrationCallback,
    ) {
        val serviceInfo =
            NsdServiceInfo().apply {
                serviceName = info.instanceName
                serviceType = info.serviceType
                port = info.port
                info.attributes.forEach { (key, value) -> setAttribute(key, value) }
            }
        val newListener = Listener(callback)
        synchronized(lock) {
            listener = newListener
            try {
                nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, newListener)
            } catch (e: Exception) {
                listener = null
                throw e
            }
        }
    }

    override fun unregister() {
        val active = synchronized(lock) { listener.also { listener = null } } ?: return
        nsdManager.unregisterService(active)
    }

    private inner class Listener(
        private val callback: RegistrationCallback,
    ) : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            callback.onRegistered(serviceInfo.serviceName)
        }

        override fun onRegistrationFailed(
            serviceInfo: NsdServiceInfo,
            errorCode: Int,
        ) {
            // A listener whose registration failed is not registered; unregistering it would throw.
            synchronized(lock) { if (listener === this) listener = null }
            callback.onFailed("NSD registration failed (error $errorCode)")
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            callback.onUnregistered()
        }

        override fun onUnregistrationFailed(
            serviceInfo: NsdServiceInfo,
            errorCode: Int,
        ) {
            callback.onFailed("NSD unregistration failed (error $errorCode)")
        }
    }
}
