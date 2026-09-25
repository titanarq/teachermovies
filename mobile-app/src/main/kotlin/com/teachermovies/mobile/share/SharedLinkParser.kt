package com.teachermovies.mobile.share

/**
 * Pulls a magnet link out of whatever text another app shares with the phone app (for example
 * `"Movie title magnet:?xt=urn:btih:... "`). Pure Kotlin: no Android type.
 */
object SharedLinkParser {
    private val magnetPattern = Regex("""magnet:\?\S*""", RegexOption.IGNORE_CASE)

    private val acceptedTopics = listOf("urn:btih:", "urn:btmh:")

    /**
     * Returns the first `magnet:?` URI found anywhere in [text] (case-insensitive, cut at the first
     * whitespace character) whose query has an `xt` parameter starting with `urn:btih:` or
     * `urn:btmh:`; returns `null` for null, blank or magnet-less text and for a magnet without
     * such an `xt`. Only the first magnet is considered: if it has no valid `xt`, the result is
     * `null` even when a later one would qualify.
     */
    fun extractMagnet(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val magnet = magnetPattern.find(text)?.value ?: return null
        return magnet.takeIf { hasBitTorrentTopic(it) }
    }

    private fun hasBitTorrentTopic(magnet: String): Boolean {
        val query = magnet.substring(magnet.indexOf('?') + 1)
        return query.split('&').any { parameter ->
            val name = parameter.substringBefore('=')
            val value = parameter.substringAfter('=', missingDelimiterValue = "")
            name.equals("xt", ignoreCase = true) &&
                acceptedTopics.any { value.startsWith(it, ignoreCase = true) }
        }
    }
}
