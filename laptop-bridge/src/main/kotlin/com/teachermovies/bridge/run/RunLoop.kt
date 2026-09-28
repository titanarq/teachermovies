package com.teachermovies.bridge.run

import com.teachermovies.bridge.config.BridgeConfig
import com.teachermovies.bridge.config.BridgeConfigStore
import com.teachermovies.bridge.config.ConfigSave
import com.teachermovies.bridge.protocol.BridgeCancelDto
import com.teachermovies.bridge.protocol.BridgeJobProtocol
import com.teachermovies.bridge.protocol.BridgeJobResultDto
import com.teachermovies.bridge.protocol.BridgeSubtitleProtocol
import com.teachermovies.bridge.subtitles.SubtitleFetchLoop
import com.teachermovies.bridge.subtitles.SubtitleTrigger
import com.teachermovies.bridge.tv.ApiFailure
import com.teachermovies.bridge.tv.ApiResult
import com.teachermovies.bridge.tv.JobStreamEnd
import com.teachermovies.bridge.tv.SseEvent
import com.teachermovies.bridge.tv.TvApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * `teachermovies-bridge run` (#277, ADR-0005 §2): holds the TV's job stream open for as long as the
 * process lives. Each `job` frame goes through [registry] in a coroutine of its own and its result
 * is posted back with `POST /api/bridge/jobs/{id}/result`; a `cancel` frame cancels that coroutine,
 * and so does the end of the stream the job came on (the TV has already resolved it `Disconnected`,
 * so an answer would only get a 409).
 *
 * When the stream ends or cannot be opened the loop waits [backoff]'s next delay and reconnects;
 * the delay resets once a stream opens again. After [discoveryAfter] consecutive attempts in which
 * the saved URL did not answer at all, each further one first asks [discovery] where the TV is now,
 * checks every candidate with the public `GET /api/status` and then with this bridge's token, and
 * switches to -- and saves in [store] -- the first one that passes. The public check comes first so
 * the token is only ever sent to something that answers like a teachermovies TV.
 *
 * Every accepted stream and every `subtitles-needed` frame (#280) also go to [subtitles], the fetch
 * loop of #282: a laptop that was off reads the whole needs list as soon as it reconnects.
 *
 * [run] returns only when the TV refuses the token (401): that is a new pairing to do, not a
 * connection to retry. Everything worth knowing goes to [log].
 */
class RunLoop(
    private val api: TvApi,
    private val store: BridgeConfigStore,
    private val registry: JobHandlerRegistry,
    private val log: RunLog,
    private val discovery: TvDiscovery,
    private val backoff: Backoff = Backoff(),
    private val discoveryAfter: Int = DEFAULT_DISCOVERY_AFTER,
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
    private val subtitles: SubtitleTrigger = SubtitleTrigger.NONE,
) {
    /** How [run] ended; the only way out of the loop is a token the TV no longer accepts. */
    sealed interface End {
        data object Unauthorized : End
    }

    suspend fun run(paired: BridgeConfig): End {
        var config = paired
        val token = requireNotNull(config.token) { "run needs a paired config" }
        var unreachable = 0
        log.line(
            "Arranca el puente: TV ${config.tvUrl}; trabajos atendidos: " +
                registry.kinds
                    .sorted()
                    .joinToString()
                    .ifEmpty { "ninguno" } + ".",
        )
        while (true) {
            val url = requireNotNull(config.tvUrl) { "run needs a paired config" }
            val end = session(url, token) { unreachable = 0 }
            when (end) {
                JobStreamEnd.Closed -> {
                    log.line("La TV ha cerrado el canal de trabajos.")
                }

                is JobStreamEnd.Broken -> {
                    log.line("Se ha cortado el canal de trabajos (${end.reason}).")
                }

                is JobStreamEnd.NotOpened -> {
                    when (val failure = end.failure) {
                        ApiFailure.Unauthorized -> {
                            log.line("La TV ha rechazado el token (401): hay que volver a emparejar con 'pair'.")
                            return End.Unauthorized
                        }

                        is ApiFailure.Http -> {
                            val code = failure.code?.let { " $it" } ?: ""
                            log.line("La TV no abre el canal de trabajos: ${failure.status}$code.")
                        }

                        is ApiFailure.Network -> {
                            unreachable++
                            log.line("La TV no responde en $url (${failure.reason}).")
                            if (unreachable >= discoveryAfter) {
                                val moved = rediscover(config, token)
                                if (moved != null) {
                                    config = moved
                                    unreachable = 0
                                    backoff.reset()
                                    continue
                                }
                            }
                        }
                    }
                }
            }
            val wait = backoff.next()
            log.line("Reintento en ${wait.inWholeMilliseconds} ms.")
            sleep(wait)
        }
    }

    /** One stream, from opening it to its end; every job still running is cancelled when it ends. */
    private suspend fun session(
        url: String,
        token: String,
        onOpen: () -> Unit,
    ): JobStreamEnd =
        coroutineScope {
            val running = ConcurrentHashMap<String, Job>()
            val end =
                api.jobStream(
                    baseUrl = url,
                    token = token,
                    onOpen = {
                        onOpen()
                        backoff.reset()
                        log.line("Conectado a la TV $url; esperando trabajos.")
                        subtitles.request(SubtitleFetchLoop.CONNECT)
                    },
                    onEvent = { event -> onEvent(event, url, token, running) },
                )
            if (running.isNotEmpty()) log.line("Se cancelan ${running.size} trabajos en curso.")
            coroutineContext.cancelChildren()
            end
        }

    private fun CoroutineScope.onEvent(
        event: SseEvent,
        url: String,
        token: String,
        running: MutableMap<String, Job>,
    ) {
        when (event.event) {
            BridgeJobProtocol.JOB_EVENT -> startJob(event.data, url, token, running)
            BridgeJobProtocol.CANCEL_EVENT -> cancelJob(event.data, running)
            BridgeSubtitleProtocol.SUBTITLES_NEEDED_EVENT -> subtitles.request(SubtitleFetchLoop.NUDGE)
            else -> Unit
        }
    }

    private fun CoroutineScope.startJob(
        data: String,
        url: String,
        token: String,
        running: MutableMap<String, Job>,
    ) {
        val job = parseObject(data)
        val id = (job?.get(ID_FIELD) as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (job == null || id.isNullOrEmpty()) {
            log.line("Trabajo ilegible, sin id que responder: se ignora.")
            return
        }
        val kind = (job[KIND_FIELD] as? JsonPrimitive)?.content ?: "?"
        val name = "$kind ${id.take(ID_PREFIX_CHARS)}"
        log.line("Trabajo $name recibido.")
        val started = TimeSource.Monotonic.markNow()
        val work =
            launch(start = CoroutineStart.LAZY) {
                try {
                    val result = registry.dispatch(job)
                    val posted = api.postJobResult(url, token, id, result)
                    log.line(
                        "Trabajo $name: ${describe(
                            result,
                        )} en ${started.elapsedNow().inWholeMilliseconds} ms; ${describe(posted)}.",
                    )
                } finally {
                    running.remove(id)
                }
            }
        // Registered before it starts, so a job that finishes at once still removes its own entry.
        running.put(id, work)?.cancel()
        work.start()
    }

    private fun cancelJob(
        data: String,
        running: MutableMap<String, Job>,
    ) {
        val id =
            try {
                JSON.decodeFromString(BridgeCancelDto.serializer(), data).id
            } catch (_: SerializationException) {
                log.line("Cancelación ilegible: se ignora.")
                return
            } catch (_: IllegalArgumentException) {
                log.line("Cancelación ilegible: se ignora.")
                return
            }
        val job = running.remove(id)
        log.line(
            "La TV cancela el trabajo ${id.take(ID_PREFIX_CHARS)}" +
                if (job == null) " (ya no estaba en curso)." else ".",
        )
        job?.cancel()
    }

    /** The config pointing at where the TV is now, already saved, or null when it was not found. */
    private suspend fun rediscover(
        config: BridgeConfig,
        token: String,
    ): BridgeConfig? {
        log.line("Buscando la TV en la red local (mDNS)...")
        val candidates = discovery.candidates().filter { it != config.tvUrl }
        for (candidate in candidates) {
            if (api.status(candidate) !is ApiResult.Success) continue
            if (api.logs(candidate, token, since = 0, level = null, limit = 1) !is ApiResult.Success) {
                log.line("$candidate es una TV, pero no acepta el token de este portátil: se descarta.")
                continue
            }
            val moved = config.copy(tvUrl = candidate)
            when (val saved = store.save(moved)) {
                is ConfigSave.Saved -> {
                    log.line(
                        "La TV ha cambiado de dirección: ahora $candidate (guardado en ${saved.path}).",
                    )
                }

                is ConfigSave.Failed -> {
                    log.line(
                        "La TV está ahora en $candidate; no se ha podido guardar (${saved.reason}).",
                    )
                }
            }
            return moved
        }
        log.line("No se ha encontrado la TV en la red local (${candidates.size} candidatos).")
        return null
    }

    private fun parseObject(data: String): JsonObject? =
        try {
            JSON.parseToJsonElement(data) as? JsonObject
        } catch (_: SerializationException) {
            null
        }

    private fun describe(result: BridgeJobResultDto): String =
        when (result) {
            is BridgeJobResultDto.Done -> "hecho"
            is BridgeJobResultDto.Failed -> "error ${result.code}"
        }

    private fun describe(posted: ApiResult<Unit>): String =
        when (posted) {
            is ApiResult.Success -> {
                "respuesta entregada"
            }

            is ApiResult.Failure -> {
                when (val failure = posted.failure) {
                    ApiFailure.Unauthorized -> {
                        "la TV rechaza la respuesta (401)"
                    }

                    is ApiFailure.Http -> {
                        "la TV rechaza la respuesta (${failure.status}${failure.code?.let { " $it" } ?: ""})"
                    }

                    is ApiFailure.Network -> {
                        "no se ha podido entregar la respuesta (${failure.reason})"
                    }
                }
            }
        }

    companion object {
        /** Consecutive "the saved URL did not answer" attempts before the first mDNS browse. */
        const val DEFAULT_DISCOVERY_AFTER = 2

        private const val ID_FIELD = "id"
        private const val KIND_FIELD = "kind"
        private const val ID_PREFIX_CHARS = 8

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
