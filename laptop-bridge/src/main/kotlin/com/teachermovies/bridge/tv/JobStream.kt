package com.teachermovies.bridge.tv

/** One complete frame of an SSE stream: its `event:` name (`message` when absent) and its `data`. */
data class SseEvent(
    val event: String,
    val data: String,
)

/** How [TvApi.jobStream] ended; it never throws. */
sealed interface JobStreamEnd {
    /** The TV accepted the stream and later closed it (restart, a newer bridge stream, "Olvidar portátil"). */
    data object Closed : JobStreamEnd

    /** The TV accepted the stream, then the connection failed or went silent; [reason] as in [ApiFailure.Network]. */
    data class Broken(
        val reason: String,
    ) : JobStreamEnd

    /** The TV never accepted the stream: unreachable ([ApiFailure.Network]), 401 or another status. */
    data class NotOpened(
        val failure: ApiFailure,
    ) : JobStreamEnd
}

/**
 * The part of the SSE wire format (WHATWG "server-sent events") the TV's `respondTextWriter`
 * streams use: `event:` and `data:` fields, `:` comments (the TV's `: ping`), one optional space
 * after the colon, several `data:` lines joined with `\n`, and a blank line ending the frame. `id:`
 * and `retry:` are ignored, since the TV sends neither; a frame without data is dropped.
 */
class SseParser {
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** Feeds one line (without its terminator) and returns the frame it completes, if any. */
    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "event" -> {
                event = value
            }

            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    private fun dispatch(): SseEvent? {
        val frame = if (hasData) SseEvent(event?.takeIf { it.isNotEmpty() } ?: DEFAULT_EVENT, data.toString()) else null
        event = null
        data.setLength(0)
        hasData = false
        return frame
    }

    private companion object {
        const val DEFAULT_EVENT = "message"
    }
}
