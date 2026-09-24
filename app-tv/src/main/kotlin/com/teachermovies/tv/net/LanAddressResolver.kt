package com.teachermovies.tv.net

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/**
 * One network interface as [LanAddressResolver.pick] sees it: a plain value, so the choice of
 * address is a pure function a JVM test can drive without the host's real interfaces.
 */
data class NetIf(
    val name: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val addresses: List<InetAddress>,
)

/**
 * Finds the LAN address a phone must open to reach the TV's HTTP server (`http://<ip>:<port>`).
 *
 * [interfaces] is where [current] reads the interfaces from; it defaults to the host's real ones
 * through [NetworkInterface.getNetworkInterfaces], and tests pass a fixed list instead.
 */
class LanAddressResolver(
    private val interfaces: () -> List<NetIf> = ::systemInterfaces,
) {
    /**
     * The first site-local IPv4 address (10/8, 172.16/12, 192.168/16) of an interface that is up
     * and not loopback, or null when there is none. Wired Ethernet (`eth*`) wins over Wi-Fi
     * (`wlan*`), which wins over anything else (a VPN `tun*`, for instance); within the same rank
     * the order of [interfaces] decides. IPv6 addresses are never returned: the phone is told an
     * IPv4 URL.
     */
    fun pick(interfaces: List<NetIf>): String? =
        interfaces
            .filter { it.isUp && !it.isLoopback }
            .mapNotNull { netIf -> netIf.addresses.firstOrNull(::isLanIpv4)?.let { netIf.name to it } }
            .minByOrNull { (name, _) -> rank(name) }
            ?.second
            ?.hostAddress

    /** [pick] over the interfaces the device has right now. */
    fun current(): String? = pick(interfaces())

    private fun isLanIpv4(address: InetAddress): Boolean = address is Inet4Address && address.isSiteLocalAddress

    private fun rank(name: String): Int =
        when {
            name.startsWith("eth") -> 0
            name.startsWith("wlan") -> 1
            else -> 2
        }
}

/**
 * The host's interfaces. A [SocketException] while listing them means the OS could not enumerate
 * them at all, which for the first-run screen is the same as having no network: it becomes an
 * empty list, and the screen shows `Sin red`.
 */
private fun systemInterfaces(): List<NetIf> {
    val all =
        try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: SocketException) {
            return emptyList()
        }
    return all.mapNotNull { netIf ->
        try {
            NetIf(
                name = netIf.name,
                isUp = netIf.isUp,
                isLoopback = netIf.isLoopback,
                addresses = netIf.inetAddresses.toList(),
            )
        } catch (e: SocketException) {
            // The interface went away between listing it and reading its flags: it is not usable.
            null
        }
    }
}
