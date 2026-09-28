package com.teachermovies.bridge.run

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Where the TV might be now (#277): base URLs (`http://<ip>:<port>`) of every service on the LAN
 * that looks like a teachermovies TV. Only candidates -- the run loop still asks each one whether
 * it is a TV and whether it accepts this bridge's token before switching to it.
 */
fun interface TvDiscovery {
    suspend fun candidates(): List<String>
}

/**
 * [TvDiscovery] over JmDNS (ADR-0004 row "JmDNS"): browses `_http._tcp` -- the service `:discovery`
 * announces through Android NSD, which publishes no `movieassistant.local` host name -- for
 * [browseTime] on every up, non-loopback, multicast-capable IPv4 interface of the laptop, and keeps
 * the instances whose name starts with `Movie Assistant` (`ServiceNames.instanceName`, which mDNS may
 * suffix on a conflict).
 *
 * One JmDNS per interface address, because `JmDNS.create()` binds to whatever `getLocalHost()`
 * resolves to, which on Debian-family laptops is `127.0.1.1`, where no TV answers. Every instance is
 * closed before [candidates] returns. A failure to browse is an empty list, never an exception.
 */
class JmDnsDiscovery(
    private val browseTime: Duration = 5.seconds,
) : TvDiscovery {
    override suspend fun candidates(): List<String> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                lanAddresses()
                    .map { address -> async { browse(address) } }
                    .awaitAll()
                    .flatten()
                    .distinct()
            }
        }

    private fun browse(address: InetAddress): List<String> =
        try {
            JmDNS.create(address).use { jmdns ->
                jmdns.list(SERVICE_TYPE, browseTime.inWholeMilliseconds).flatMap(::urlsOf)
            }
        } catch (_: IOException) {
            emptyList()
        }

    companion object {
        /** DNS-SD type the TV announces, fully qualified the way JmDNS names it. */
        const val SERVICE_TYPE = "_http._tcp.local."

        /** The prefix of every instance name the TV announces (`:discovery`'s `ServiceNames`). */
        const val INSTANCE_PREFIX = "Movie Assistant"

        /** Whether an announced instance [name] is a teachermovies TV's. */
        fun isTvInstance(name: String?): Boolean = name != null && name.startsWith(INSTANCE_PREFIX)

        /** The base URLs of [info], one per IPv4 address, or none when it is not a TV's. */
        fun urlsOf(info: ServiceInfo): List<String> {
            if (!isTvInstance(info.name) || info.port !in 1..MAX_PORT) return emptyList()
            return info.inet4Addresses.map { baseUrl(it, info.port) }
        }

        fun baseUrl(
            address: Inet4Address,
            port: Int,
        ): String = "http://${address.hostAddress}:$port"

        private const val MAX_PORT = 65_535

        private fun lanAddresses(): List<InetAddress> =
            try {
                NetworkInterface
                    .getNetworkInterfaces()
                    .toList()
                    .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
                    .flatMap { it.inetAddresses.toList() }
                    .filter { it is Inet4Address && !it.isLoopbackAddress }
            } catch (_: SocketException) {
                emptyList()
            }
    }
}
