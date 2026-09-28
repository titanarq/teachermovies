package com.teachermovies.bridge.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import javax.jmdns.ServiceInfo

/**
 * The pure parts of the mDNS fallback (#277). The browse itself needs a LAN with multicast and a TV
 * announcing on it, so it is a manual check, not a JVM test.
 */
class JmDnsDiscoveryTest {
    @Test
    fun `only instances named like the TV's announcement are candidates`() {
        assertTrue(JmDnsDiscovery.isTvInstance("Movie Assistant"))
        assertTrue(JmDnsDiscovery.isTvInstance("Movie Assistant (Salón) (2)"))
        assertFalse(JmDnsDiscovery.isTvInstance("HP LaserJet"))
        assertFalse(JmDnsDiscovery.isTvInstance(null))
    }

    @Test
    fun `another device's http service gives no URL`() {
        val printer = ServiceInfo.create(JmDnsDiscovery.SERVICE_TYPE, "HP LaserJet", 80, "")
        assertEquals(emptyList<String>(), JmDnsDiscovery.urlsOf(printer))
    }

    @Test
    fun `a base URL is the IPv4 address and the announced port`() {
        val address = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20)) as Inet4Address
        assertEquals("http://192.168.1.20:8787", JmDnsDiscovery.baseUrl(address, 8787))
    }

    @Test
    fun `the TV's service type is the one discovery announces`() {
        assertEquals("_http._tcp.local.", JmDnsDiscovery.SERVICE_TYPE)
    }
}
