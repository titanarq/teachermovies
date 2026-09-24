package com.teachermovies.tv.net

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanAddressResolverTest {

    private val resolver = LanAddressResolver(interfaces = { emptyList() })

    @Test
    fun ethIsPreferredOverWlanWhateverTheOrder() {
        val interfaces =
            listOf(
                netIf("wlan0", ipv4(192, 168, 1, 50)),
                netIf("eth0", ipv4(192, 168, 1, 20)),
            )

        assertEquals("192.168.1.20", resolver.pick(interfaces))
    }

    @Test
    fun wlanIsPreferredOverOtherInterfaces() {
        val interfaces =
            listOf(
                netIf("tun0", ipv4(10, 8, 0, 2)),
                netIf("wlan0", ipv4(192, 168, 1, 50)),
            )

        assertEquals("192.168.1.50", resolver.pick(interfaces))
    }

    @Test
    fun otherInterfaceIsUsedWhenNeitherEthNorWlanHasAnAddress() {
        assertEquals("172.16.4.9", resolver.pick(listOf(netIf("rmnet0", ipv4(172, 16, 4, 9)))))
    }

    @Test
    fun firstInterfaceOfTheSameRankWins() {
        val interfaces =
            listOf(
                netIf("eth1", ipv4(10, 0, 0, 2)),
                netIf("eth0", ipv4(10, 0, 0, 1)),
            )

        assertEquals("10.0.0.2", resolver.pick(interfaces))
    }

    @Test
    fun loopbackIsIgnored() {
        val interfaces =
            listOf(
                netIf("lo", ipv4(127, 0, 0, 1), isLoopback = true),
                // A loopback-flagged interface is not the LAN even if it carries a site-local address.
                netIf("eth9", ipv4(192, 168, 0, 1), isLoopback = true),
                netIf("wlan0", ipv4(192, 168, 1, 50)),
            )

        assertEquals("192.168.1.50", resolver.pick(interfaces))
    }

    @Test
    fun downInterfaceIsIgnored() {
        val interfaces =
            listOf(
                netIf("eth0", ipv4(192, 168, 1, 20), isUp = false),
                netIf("wlan0", ipv4(192, 168, 1, 50)),
            )

        assertEquals("192.168.1.50", resolver.pick(interfaces))
    }

    @Test
    fun ipv6IsIgnoredEvenWhenSiteLocal() {
        // fec0::1, which `isSiteLocalAddress` reports as site-local.
        val bytes = ByteArray(16)
        bytes[0] = 0xfe.toByte()
        bytes[1] = 0xc0.toByte()
        bytes[15] = 1
        val siteLocalV6 = InetAddress.getByAddress(bytes)

        assertEquals("192.168.1.20", resolver.pick(listOf(netIf("eth0", siteLocalV6, ipv4(192, 168, 1, 20)))))
        assertNull(resolver.pick(listOf(netIf("eth0", siteLocalV6))))
    }

    @Test
    fun publicIpv4IsNotALanAddress() {
        assertNull(resolver.pick(listOf(netIf("eth0", ipv4(8, 8, 8, 8)))))
    }

    @Test
    fun noUsableInterfaceMeansNull() {
        assertNull(resolver.pick(emptyList()))
        assertNull(resolver.pick(listOf(netIf("eth0"))))
    }

    @Test
    fun currentPicksFromTheSuppliedInterfaces() {
        val live = LanAddressResolver(interfaces = { listOf(netIf("wlan0", ipv4(192, 168, 1, 50))) })

        assertEquals("192.168.1.50", live.current())
        assertNull(resolver.current())
    }

    private fun netIf(
        name: String,
        vararg addresses: InetAddress,
        isUp: Boolean = true,
        isLoopback: Boolean = false,
    ) = NetIf(name = name, isUp = isUp, isLoopback = isLoopback, addresses = addresses.toList())

    private fun ipv4(a: Int, b: Int, c: Int, d: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))
}
