package com.teachermovies.discovery

/** Builds the DNS-SD instance name the TV announces itself under. */
object ServiceNames {
    /** The name used when there is no usable device name. */
    const val BASE_NAME = "Movie Assistant"

    /** mDNS limits a DNS label, and so a service instance name, to 63 bytes. */
    const val MAX_INSTANCE_NAME_BYTES = 63

    private const val PREFIX = "$BASE_NAME ("
    private const val SUFFIX = ")"
    private val WHITESPACE_RUN = Regex("\\s+")

    /**
     * `Movie Assistant` for a null or blank [deviceName], `Movie Assistant (<deviceName>)`
     * otherwise, with runs of whitespace in the device name collapsed to one space. The device
     * name is truncated so the whole result is at most [MAX_INSTANCE_NAME_BYTES] bytes in UTF-8,
     * never splitting a code point.
     */
    fun instanceName(deviceName: String?): String {
        val device = deviceName?.replace(WHITESPACE_RUN, " ")?.trim().orEmpty()
        if (device.isEmpty()) return BASE_NAME

        val budget = MAX_INSTANCE_NAME_BYTES - utf8Length(PREFIX) - utf8Length(SUFFIX)
        val truncated = truncateUtf8(device, budget).trimEnd()
        return if (truncated.isEmpty()) BASE_NAME else "$PREFIX$truncated$SUFFIX"
    }

    private fun truncateUtf8(
        text: String,
        maxBytes: Int,
    ): String {
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            val size = utf8Length(codePoint)
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(codePoint)
        }
        return text.substring(0, end)
    }

    private fun utf8Length(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    private fun utf8Length(codePoint: Int): Int =
        when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
}
