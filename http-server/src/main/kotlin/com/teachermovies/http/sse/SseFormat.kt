package com.teachermovies.http.sse

/**
 * The wire format of a Server-Sent Event (`GET /api/events`, #62), factored out so it is unit
 * tested without an HTTP round-trip.
 */
object SseFormat {
    /**
     * One `event: [name]` frame carrying [data]: a multi-line [data] becomes one `data:` line per
     * input line (the SSE spec joins them back into a single field on the client), and the frame
     * ends with the blank line that terminates it. The result always ends with `"\n\n"`.
     */
    fun event(
        name: String,
        data: String,
    ): String {
        val dataLines = data.lineSequence().joinToString(separator = "\n") { line -> "data: $line" }
        return "event: $name\n$dataLines\n\n"
    }

    /** A comment line clients ignore, sent periodically so idle proxies/clients don't time out. */
    const val PING: String = ": ping\n\n"
}
