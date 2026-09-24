package com.teachermovies.http.auth

/**
 * Whether a remote address belongs to the local network (#59): the HTTP server answers only
 * clients it believes are on the LAN, even if a port forward exposes `:8787` to the Internet.
 *
 * [isAllowed] parses its argument by hand as an IPv4 or IPv6 literal and never calls a
 * DNS-resolving API, so a hostname or any other unparsable string is refused without ever
 * touching the network.
 *
 * Allowed: loopback (`127.0.0.0/8`, `::1`), private IPv4 (`10.0.0.0/8`, `172.16.0.0/12`,
 * `192.168.0.0/16`), link-local IPv4 (`169.254.0.0/16`), IPv6 link-local (`fe80::/10`) and
 * unique-local (`fc00::/7`), and the IPv4-mapped IPv6 form (`::ffff:a.b.c.d`) of any allowed IPv4
 * address. Everything else -- including a syntactically invalid or unrecognised address -- is
 * refused.
 */
object LanAddressPolicy {
    fun isAllowed(address: String): Boolean {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return false
        ipv4ToBytes(trimmed)?.let { return isAllowedIpv4(it) }
        ipv6ToBytes(trimmed)?.let { return isAllowedIpv6(it) }
        return false
    }

    private fun isAllowedIpv4(b: ByteArray): Boolean {
        val b0 = b[0].unsigned()
        val b1 = b[1].unsigned()
        return b0 == 127 || // 127.0.0.0/8 loopback
            b0 == 10 || // 10.0.0.0/8
            (b0 == 172 && b1 in 16..31) || // 172.16.0.0/12
            (b0 == 192 && b1 == 168) || // 192.168.0.0/16
            (b0 == 169 && b1 == 254) // 169.254.0.0/16 link-local
    }

    private fun isAllowedIpv6(b: ByteArray): Boolean {
        val isLoopback = (0..14).all { b[it] == ZERO_BYTE } && b[15] == ONE_BYTE
        if (isLoopback) return true // ::1

        val b0 = b[0].unsigned()
        val b1 = b[1].unsigned()
        if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true // fe80::/10
        if ((b0 and 0xFE) == 0xFC) return true // fc00::/7

        val isIpv4Mapped =
            (0..9).all { b[it] == ZERO_BYTE } && b[10] == FF_BYTE && b[11] == FF_BYTE
        if (isIpv4Mapped) return isAllowedIpv4(b.copyOfRange(12, 16))

        return false
    }

    private fun Byte.unsigned(): Int = toInt() and 0xFF

    /** Strict dotted-quad: four 0-255 decimal groups, no leading zeros, nothing else. */
    private fun ipv4ToBytes(input: String): ByteArray? {
        val parts = input.split(".")
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in parts.indices) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            if (part.length > 1 && part[0] == '0') return null // reject e.g. "010" (octal-looking)
            val value = part.toInt()
            if (value !in 0..255) return null
            bytes[i] = value.toByte()
        }
        return bytes
    }

    private val HEX_GROUP = Regex("^[0-9a-fA-F]{1,4}$")

    /**
     * Hand-rolled IPv6 literal parser: at most one `::` compression, 1-4 hex digit groups, and an
     * optional embedded IPv4 tail (`::ffff:192.168.1.2`). Returns `null` for anything that isn't a
     * syntactically valid IPv6 literal -- never falls back to a resolver.
     */
    private fun ipv6ToBytes(input: String): ByteArray? {
        var addr = input.substringBefore('%') // drop a zone id (e.g. "fe80::1%eth0"); never relevant here
        if (addr.isEmpty()) return null

        val lastColon = addr.lastIndexOf(':')
        if (lastColon >= 0 && addr.substring(lastColon + 1).contains('.')) {
            val v4 = ipv4ToBytes(addr.substring(lastColon + 1)) ?: return null
            val hi = (v4[0].unsigned() shl 8) or v4[1].unsigned()
            val lo = (v4[2].unsigned() shl 8) or v4[3].unsigned()
            addr = addr.substring(0, lastColon + 1) + "%x:%x".format(hi, lo)
        }

        if (Regex("::").findAll(addr).count() > 1) return null

        val groups: List<String> =
            if (addr.contains("::")) {
                val idx = addr.indexOf("::")
                val left = addr.substring(0, idx)
                val right = addr.substring(idx + 2)
                val leftGroups = if (left.isEmpty()) emptyList() else left.split(":")
                val rightGroups = if (right.isEmpty()) emptyList() else right.split(":")
                if (leftGroups.any { it.isEmpty() } || rightGroups.any { it.isEmpty() }) return null
                val missing = HEXTET_COUNT - leftGroups.size - rightGroups.size
                if (missing < 1) return null // "::" must stand for at least one 16-bit group
                leftGroups + List(missing) { "0" } + rightGroups
            } else {
                addr.split(":")
            }

        if (groups.size != HEXTET_COUNT || groups.any { !HEX_GROUP.matches(it) }) return null

        val bytes = ByteArray(16)
        for (i in 0 until HEXTET_COUNT) {
            val v = groups[i].toInt(16)
            bytes[i * 2] = (v shr 8).toByte()
            bytes[i * 2 + 1] = (v and 0xFF).toByte()
        }
        return bytes
    }

    private const val HEXTET_COUNT = 8
    private val ZERO_BYTE = 0.toByte()
    private val ONE_BYTE = 1.toByte()
    private val FF_BYTE = 0xFF.toByte()
}
