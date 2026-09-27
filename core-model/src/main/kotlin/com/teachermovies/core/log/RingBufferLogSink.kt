package com.teachermovies.core.log

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

/**
 * The in-memory ring buffer of ADR-0006: what `GET /api/logs` pages through ([entries]) and what
 * `GET /api/logs/stream` follows live ([entries] as a [SharedFlow]).
 *
 * Every line is redacted by [redactor] and cut to [maxMessageBytes] on the way in, and the oldest
 * line goes as soon as the buffer holds [maxLines] lines or [maxBytes] bytes of message text --
 * about 1 MiB, the whole memory cost of keeping logs. Nothing is persisted: a process death loses
 * the buffer, and [bootId] changing is what tells a reader (the laptop's mirror) that [LogEntry.seq]
 * started over.
 *
 * Thread-safe: logging happens on the thread that has something to say, which for torrents and
 * playback is a native library's own. The live flow drops the oldest line a slow collector has not
 * taken yet rather than growing, and a collector that reconnects backfills through [entries].
 *
 * `AppContainer` installs exactly one per process (#268), which is what makes [bootId] a boot id.
 */
class RingBufferLogSink(
    private val maxLines: Int = MAX_LINES,
    private val maxBytes: Long = MAX_BYTES,
    private val maxMessageBytes: Int = MAX_MESSAGE_BYTES,
    private val redactor: LogRedactor = LogRedactor(),
    bootId: String = newBootId(),
) : LogSink {
    /** Identifies this process's buffer; a reader that sees it change discards its cursor. */
    val bootId: String = bootId

    private val lock = Any()
    private val stored = ArrayDeque<LogEntry>()
    private var storedBytes = 0L
    private val live =
        MutableSharedFlow<LogEntry>(
            extraBufferCapacity = LIVE_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * Every stored line, already redacted, as it is stored -- no replay, because a collector
     * backfills with [entries] and only then follows this.
     */
    val entries: SharedFlow<LogEntry> = live.asSharedFlow()

    init {
        require(maxLines > 0) { "maxLines must be positive, was $maxLines" }
        require(maxBytes > 0) { "maxBytes must be positive, was $maxBytes" }
        require(maxMessageBytes > TRUNCATION_SUFFIX.utf8Bytes()) {
            "maxMessageBytes must leave room for the truncation marker, was $maxMessageBytes"
        }
    }

    override fun record(entry: LogEntry) {
        val fitted = fit(entry)
        synchronized(lock) {
            stored.addLast(fitted)
            storedBytes += fitted.message.utf8Bytes()
            while (stored.size > maxLines || storedBytes > maxBytes) {
                storedBytes -= stored.removeFirst().message.utf8Bytes()
            }
        }
        live.tryEmit(fitted)
    }

    /**
     * The stored lines after the cursor [since], oldest first, at most [limit] of them: a reader
     * takes the page, then asks again with `since` set to the last [LogEntry.seq] it got, until a
     * page comes back short. `since = 0` (the default) reads the whole buffer, and a [since] from a
     * previous boot simply returns everything this boot has.
     *
     * [minLevel] narrows the page to that level and above; null keeps every stored level.
     */
    fun entries(
        since: Long = 0L,
        minLevel: LogLevel? = null,
        limit: Int = DEFAULT_PAGE,
    ): List<LogEntry> {
        require(limit > 0) { "limit must be positive, was $limit" }
        synchronized(lock) {
            return stored
                .filter { it.seq > since && (minLevel == null || it.level.isAtLeast(minLevel)) }
                .take(limit)
        }
    }

    private fun fit(entry: LogEntry): LogEntry {
        val message = truncate(redactor.redact(entry.message))
        return if (message == entry.message) entry else entry.copy(message = message)
    }

    /** Cuts [message] to [maxMessageBytes] of UTF-8, on a code-point boundary, marking the cut. */
    private fun truncate(message: String): String {
        if (message.utf8Bytes() <= maxMessageBytes) return message
        val budget = maxMessageBytes - TRUNCATION_SUFFIX.utf8Bytes()
        val kept = StringBuilder()
        var used = 0
        var index = 0
        while (index < message.length) {
            val end = if (message[index].isHighSurrogate() && index + 1 < message.length) index + 2 else index + 1
            val piece = message.substring(index, end)
            val pieceBytes = piece.utf8Bytes()
            if (used + pieceBytes > budget) break
            kept.append(piece)
            used += pieceBytes
            index = end
        }
        return kept.append(TRUNCATION_SUFFIX).toString()
    }

    companion object {
        /** Lines kept: the default cap of ADR-0006. */
        const val MAX_LINES = 5000

        /** Bytes of message text kept: 1 MiB, the bound ADR-0006 puts on the memory cost. */
        const val MAX_BYTES = 1024L * 1024L

        /** Bytes one message may take; a longer one is cut and marked. */
        const val MAX_MESSAGE_BYTES = 2048

        /** Lines one [entries] page returns unless the caller asks for another number. */
        const val DEFAULT_PAGE = 500

        private const val LIVE_BUFFER_CAPACITY = 64
        private const val TRUNCATION_SUFFIX = "[truncated]"

        private fun newBootId(): String = UUID.randomUUID().toString()

        private fun String.utf8Bytes(): Int = toByteArray(Charsets.UTF_8).size
    }
}
