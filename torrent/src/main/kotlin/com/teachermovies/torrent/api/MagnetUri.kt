package com.teachermovies.torrent.api

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * A magnet URI's info-hash and display name, parsed from its `xt=urn:btih:<hash>` parameter.
 *
 * Shared between [com.teachermovies.torrent.fake.FakeTorrentEngine] and the jlibtorrent-backed
 * engine (#51) so both validate and normalise magnets the same way: no jlibtorrent type is
 * involved in parsing.
 */
data class MagnetUri(
    val infoHash: String,
    val displayName: String?,
) {
    companion object {
        private val HEX_40 = Regex("^[0-9a-fA-F]{40}$")
        private val BASE32_32 = Regex("^[A-Za-z2-7]{32}$")
        private val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private const val BTIH_PREFIX = "urn:btih:"

        /**
         * Parses [uri], or returns `null` when it is not a `magnet:` URI or carries no valid
         * `xt=urn:btih:<40 hex or 32 base32>` parameter. The info-hash is always returned as
         * lower-case hex, whichever form the URI used.
         */
        fun parse(uri: String): MagnetUri? {
            if (!uri.startsWith("magnet:", ignoreCase = true)) return null
            val query = uri.substringAfter('?', missingDelimiterValue = "")
            if (query.isEmpty()) return null

            val params =
                query.split('&').mapNotNull { pair ->
                    if (pair.isEmpty()) return@mapNotNull null
                    val key = pair.substringBefore('=')
                    val rawValue = pair.substringAfter('=', missingDelimiterValue = "")
                    key to decode(rawValue)
                }

            val infoHash =
                params
                    .firstOrNull { (key, value) -> key == "xt" && value.startsWith(BTIH_PREFIX, ignoreCase = true) }
                    ?.second
                    ?.substring(BTIH_PREFIX.length)
                    ?.let { hash -> normalizeInfoHash(hash) }
                    ?: return null

            val displayName = params.firstOrNull { (key, _) -> key == "dn" }?.second

            return MagnetUri(infoHash = infoHash, displayName = displayName)
        }

        private fun normalizeInfoHash(hash: String): String? =
            when {
                HEX_40.matches(hash) -> hash.lowercase()
                BASE32_32.matches(hash) -> decodeBase32(hash.uppercase()).joinToString("") { "%02x".format(it) }
                else -> null
            }

        private fun decode(value: String): String = runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrDefault(value)

        /** Decodes RFC 4648 base32 (no padding), assuming [input] is upper-case and valid. */
        private fun decodeBase32(input: String): ByteArray {
            val bits = StringBuilder(input.length * 5)
            for (c in input) {
                val index = BASE32_ALPHABET.indexOf(c)
                bits.append(index.toString(2).padStart(5, '0'))
            }
            return ByteArray(bits.length / 8) { i -> bits.substring(i * 8, i * 8 + 8).toInt(2).toByte() }
        }
    }
}
