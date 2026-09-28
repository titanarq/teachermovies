package com.teachermovies.bridge.logs

import com.teachermovies.bridge.protocol.LogEntryDto
import com.teachermovies.bridge.protocol.LogStreamBootDto
import com.teachermovies.bridge.run.Backoff
import com.teachermovies.bridge.run.RunLog
import com.teachermovies.bridge.tv.ApiFailure
import com.teachermovies.bridge.tv.JobStreamEnd
import com.teachermovies.bridge.tv.SseEvent
import com.teachermovies.bridge.tv.TvApi
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Opens the TV's log stream; [TvApi.logStream] in production, a scripted stream in tests. */
fun interface LogStreamSource {
    suspend fun open(
        baseUrl: String,
        token: String,
        since: Long?,
        onOpen: suspend () -> Unit,
        onEvent: suspend (SseEvent) -> Boolean,
    ): JobStreamEnd

    companion object {
        fun of(api: TvApi): LogStreamSource =
            LogStreamSource {
                baseUrl,
                token,
                since,
                onOpen,
                onEvent,
                ->
                api.logStream(baseUrl, token, since, onOpen, onEvent)
            }
    }
}

/**
 * The laptop mirror of the TV log (#272, ADR-0006 §5): holds `GET /api/logs/stream` open and
 * appends every entry to [files], for as long as `run` lives.
 *
 * - The last entry written is recorded in [cursors] after each line, so a restarted bridge asks
 *   for `since = <that seq>` and neither repeats nor skips a line the TV still holds. Entries at or
 *   below the cursor are dropped, in case a TV replays one.
 * - The stream's `boot` frame names the TV process. A different `bootId` from the cursor's means
 *   the TV restarted: [TvLogFiles.gap] writes the visible marker, the cursor starts over at 0 for
 *   the new boot, and a stream that was opened with a positive `since` -- and so is missing the new
 *   boot's first lines -- is dropped and reopened at once from `since = 0`.
 * - Any other end of the stream is retried after [backoff]'s next delay, 1 s doubling up to 30 s
 *   by default and back to 1 s once a stream opens. A failed write stops the stream too: the cursor
 *   has not moved, so the retry asks again for the line that could not be written.
 * - A 401 ends the mirror ([End.Unauthorized]); the job loop, which sees the same refusal, is what
 *   ends the process.
 *
 * [baseUrl] is asked again before every attempt, so the mirror follows a TV the job loop has found
 * at a new address. Its own events go to [log], each different problem once until the stream opens
 * again, so a TV without the route does not print a line every 30 s.
 */
class TvLogMirror(
    private val source: LogStreamSource,
    private val files: TvLogFiles,
    private val cursors: MirrorCursorStore,
    private val log: RunLog,
    private val backoff: Backoff = Backoff(max = MAX_BACKOFF),
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
) {
    sealed interface End {
        /** The TV refused the token (401). */
        data object Unauthorized : End
    }

    private var lastProblem: String? = null

    suspend fun run(
        baseUrl: suspend () -> String,
        token: String,
    ): End {
        var cursor = cursors.load()
        while (true) {
            val attempt = Attempt(cursor)
            val end =
                source.open(baseUrl(), token, cursor?.seq, onOpen = {
                    backoff.reset()
                    lastProblem = null
                    log.line("Copiando el registro de la TV en ${files.dir}.")
                }) { attempt.onEvent(it) }
            cursor = attempt.cursor
            if (attempt.reopenFromStart) continue
            when (end) {
                JobStreamEnd.Closed -> {
                    if (!attempt.writeFailed) problem("La TV ha cerrado el registro.")
                }

                is JobStreamEnd.Broken -> {
                    problem("Se ha cortado el registro de la TV (${end.reason}).")
                }

                is JobStreamEnd.NotOpened -> {
                    when (val failure = end.failure) {
                        ApiFailure.Unauthorized -> {
                            log.line("La TV ha rechazado el token al pedir el registro (401): se deja de copiar.")
                            return End.Unauthorized
                        }

                        is ApiFailure.Http -> {
                            problem(
                                "La TV no abre el registro: ${failure.status}${failure.code?.let { " $it" } ?: ""}.",
                            )
                        }

                        is ApiFailure.Network -> {
                            problem("La TV no responde al pedir el registro (${failure.reason}).")
                        }
                    }
                }
            }
            sleep(backoff.next())
        }
    }

    private fun problem(message: String) {
        if (message == lastProblem) return
        lastProblem = message
        log.line(message)
    }

    /** One opened stream: what it did to the cursor, and whether it must be reopened from 0. */
    private inner class Attempt(
        var cursor: MirrorCursor?,
    ) {
        private val since = cursor?.seq ?: 0L
        private var boot: String? = null
        var reopenFromStart = false
        var writeFailed = false

        fun onEvent(event: SseEvent): Boolean =
            try {
                when (event.event) {
                    BOOT_EVENT -> onBoot(JSON.decodeFromString(LogStreamBootDto.serializer(), event.data).bootId)
                    LOG_EVENT -> onEntry(JSON.decodeFromString(LogEntryDto.serializer(), event.data))
                    else -> true
                }
            } catch (_: SerializationException) {
                problem("Línea del registro de la TV ilegible: se ignora.")
                true
            } catch (_: IllegalArgumentException) {
                problem("Línea del registro de la TV ilegible: se ignora.")
                true
            } catch (e: IOException) {
                writeFailed = true
                problem("No se puede escribir la copia del registro en ${files.dir} (${e::class.simpleName}).")
                false
            }

        private fun onBoot(bootId: String): Boolean {
            boot = bootId
            val previous = cursor
            if (previous == null) {
                cursor = MirrorCursor(bootId, 0)
                return true
            }
            if (previous.bootId == bootId) return true
            files.gap(previous.bootId, bootId)
            val restarted = MirrorCursor(bootId, 0)
            cursors.save(restarted)
            cursor = restarted
            log.line("La TV ha vuelto a arrancar: se marca el corte en el registro.")
            if (since > 0) {
                reopenFromStart = true
                return false
            }
            return true
        }

        private fun onEntry(entry: LogEntryDto): Boolean {
            val bootId = boot
            if (bootId == null) {
                problem("Línea del registro antes del arranque de la TV: se ignora.")
                return true
            }
            val current = cursor
            if (current != null && entry.seq <= current.seq) return true
            files.append(entry)
            val advanced = MirrorCursor(bootId, entry.seq)
            cursors.save(advanced)
            cursor = advanced
            return true
        }
    }

    companion object {
        /** The longest wait between two attempts (#272). */
        val MAX_BACKOFF: Duration = 30.seconds

        private const val BOOT_EVENT = "boot"
        private const val LOG_EVENT = "log"

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
