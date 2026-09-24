package com.teachermovies.http.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class LanAddressPolicyTest {
    private data class Case(val address: String, val allowed: Boolean)

    private val cases =
        listOf(
            // Loopback.
            Case("127.0.0.1", true),
            Case("127.255.255.255", true),
            Case("::1", true),
            // Private IPv4.
            Case("10.0.0.1", true),
            Case("10.255.255.255", true),
            Case("172.16.0.1", true),
            Case("172.31.255.255", true),
            Case("172.15.0.1", false), // just below 172.16.0.0/12
            Case("172.32.0.1", false), // just above 172.16.0.0/12
            Case("192.168.1.2", true),
            Case("192.169.0.1", false),
            // Link-local IPv4.
            Case("169.254.169.254", true),
            Case("169.253.0.1", false),
            // Public IPv4.
            Case("8.8.8.8", false),
            Case("1.1.1.1", false),
            // IPv6 link-local and unique-local.
            Case("fe80::1", true),
            Case("fe80::abcd:1", true),
            Case("febf::1", true), // top of fe80::/10
            Case("fec0::1", false), // just above fe80::/10
            Case("fc00::1", true),
            Case("fdff:ffff::1", true),
            Case("fe00::1", false), // just below fc00::/7
            // Public IPv6.
            Case("2001:db8::1", false),
            // IPv4-mapped IPv6 forms of allowed/refused IPv4 ranges.
            Case("::ffff:192.168.1.2", true),
            Case("::ffff:c0a8:0102", true), // same address, all-hex form
            Case("::ffff:8.8.8.8", false),
            // Unparsable / not an address at all.
            Case("not-an-address", false),
            Case("", false),
            Case("   ", false),
            Case("256.0.0.1", false),
            Case("192.168.1", false),
            Case("192.168.1.2.3", false),
            Case("localhost", false),
            Case("example.com", false),
            Case("1:2:3:4:5:6:7:8:9", false),
            Case("1::2::3", false),
        )

    @Test
    fun `isAllowed classifies addresses by LAN range`() {
        val failures =
            cases.filter { LanAddressPolicy.isAllowed(it.address) != it.allowed }
        assertEquals("Wrong classification for: $failures", emptyList<Case>(), failures)
    }
}
