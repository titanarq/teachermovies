package com.teachermovies.bridge.subtitles

import com.teachermovies.bridge.opensubtitles.OsFailure
import com.teachermovies.bridge.opensubtitles.SubtitleLanguage
import com.teachermovies.bridge.opensubtitles.SubtitleRequest
import com.teachermovies.bridge.opensubtitles.SubtitleSearch
import com.teachermovies.bridge.opensubtitles.SubtitleSearcher
import com.teachermovies.bridge.protocol.BridgeSubtitleProtocol
import com.teachermovies.bridge.protocol.SubtitleNeedDto
import com.teachermovies.bridge.protocol.SubtitleStatusDto
import com.teachermovies.bridge.run.RunLog
import com.teachermovies.bridge.tv.ApiFailure
import com.teachermovies.bridge.tv.ApiResult
import com.teachermovies.bridge.tv.TvApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Asks the fetch loop to work through the TV's subtitle needs soon (#282). The job loop calls it
 * when its stream opens and on every `subtitles-needed` frame; it never blocks.
 */
fun interface SubtitleTrigger {
    fun request(reason: String)

    companion object {
        /** For a `run` without OpenSubtitles credentials: nothing to trigger. */
        val NONE: SubtitleTrigger = SubtitleTrigger { }
    }
}

/**
 * The bridge's automatic-subtitle loop (#282, ADR-0005 §5). A pass reads the TV's
 * `GET /api/bridge/subtitle-needs` and, for each need in order, marks it `searching`, asks
 * [searcher] (OpenSubtitles, #281) and reports the result: the file uploaded through
 * `POST /api/bridge/subtitles`, or `not_found` / `failed` through `POST /api/bridge/subtitle-status`.
 *
 * Passes run one at a time, on three triggers: [request] -- the job stream opening (which is how a
 * laptop that was off catches up: the needs list holds everything that accumulated meanwhile) and
 * each `subtitles-needed` frame -- and a timer every [interval]. Requests arriving during a pass
 * collapse into one more pass after it.
 *
 * The daily quota: a pass does not start, and stops before its next need, while [searcher]'s quota
 * says no download is left before the reset. A search refused for quota is reported `failed`, so
 * the TV lists it again, and ends the pass. So do refused credentials and a rate limit, which would
 * fail every need behind them the same way. The seven-day not-found retry is the TV's to apply.
 */
class SubtitleFetchLoop(
    private val api: TvApi,
    private val searcher: SubtitleSearcher,
    private val log: RunLog,
    private val interval: Duration = DEFAULT_INTERVAL,
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
    private val now: () -> Instant = Instant::now,
) : SubtitleTrigger {
    /** What one pass did; [stoppedBy] says why it ended before the end of the list, if it did. */
    data class Pass(
        val uploaded: Int = 0,
        val notFound: Int = 0,
        val failed: Int = 0,
        val skipped: Int = 0,
        val stoppedBy: Stop? = null,
    )

    enum class Stop {
        /** The TV's list could not be read. */
        NO_LIST,

        /** No OpenSubtitles download left until the reset. */
        QUOTA,

        /** OpenSubtitles refuses the credentials or is rate limiting. */
        OPENSUBTITLES,
    }

    private val requests = Channel<String>(Channel.CONFLATED)

    override fun request(reason: String) {
        requests.trySend(reason)
    }

    /**
     * Runs passes until cancelled. [url] is read again before every pass, so the loop follows the
     * job loop's mDNS re-discovery; [onPass] sees every pass's result (tests wait on it).
     */
    suspend fun run(
        url: () -> String,
        token: String,
        onPass: (Pass) -> Unit = {},
    ) {
        coroutineScope {
            launch {
                while (true) {
                    sleep(interval)
                    request(TIMER)
                }
            }
            while (true) {
                val reason = requests.receive()
                onPass(pass(url(), token, reason))
            }
        }
    }

    /** One pass over the needs list as it is now. */
    suspend fun pass(
        url: String,
        token: String,
        reason: String,
    ): Pass {
        if (quotaExhausted()) {
            log.line("Subtítulos ($reason): no queda cuota de OpenSubtitles hasta ${searcher.quota().resetsAt}.")
            return Pass(stoppedBy = Stop.QUOTA)
        }
        val needs =
            when (val listed = api.subtitleNeeds(url, token)) {
                is ApiResult.Success -> {
                    listed.value
                }

                is ApiResult.Failure -> {
                    log.line("Subtítulos ($reason): no se puede leer la lista de la TV (${describe(listed.failure)}).")
                    return Pass(stoppedBy = Stop.NO_LIST)
                }
            }
        if (needs.isEmpty()) {
            if (reason != TIMER) log.line("Subtítulos ($reason): la TV no necesita ninguno.")
            return Pass()
        }
        log.line("Subtítulos ($reason): ${needs.size} pendientes.")
        var pass = Pass()
        for (need in needs) {
            if (quotaExhausted()) {
                pass = pass.copy(stoppedBy = Stop.QUOTA)
                break
            }
            pass =
                when (fetch(url, token, need)) {
                    Outcome.UPLOADED -> pass.copy(uploaded = pass.uploaded + 1)
                    Outcome.NOT_FOUND -> pass.copy(notFound = pass.notFound + 1)
                    Outcome.FAILED -> pass.copy(failed = pass.failed + 1)
                    Outcome.SKIPPED -> pass.copy(skipped = pass.skipped + 1)
                    Outcome.QUOTA -> pass.copy(failed = pass.failed + 1, stoppedBy = Stop.QUOTA)
                    Outcome.OPENSUBTITLES -> pass.copy(failed = pass.failed + 1, stoppedBy = Stop.OPENSUBTITLES)
                }
            if (pass.stoppedBy != null) break
        }
        val quota = searcher.quota()
        log.line(
            "Subtítulos: ${pass.uploaded} subidos, ${pass.notFound} sin resultado, ${pass.failed} con error" +
                (if (pass.skipped > 0) ", ${pass.skipped} omitidos" else "") +
                (quota.remaining?.let { "; quedan $it descargas hoy" } ?: "") +
                (if (pass.stoppedBy == Stop.QUOTA) "; cuota agotada, se sigue tras ${quota.resetsAt}" else "") +
                ".",
        )
        return pass
    }

    private enum class Outcome { UPLOADED, NOT_FOUND, FAILED, SKIPPED, QUOTA, OPENSUBTITLES }

    private suspend fun fetch(
        url: String,
        token: String,
        need: SubtitleNeedDto,
    ): Outcome {
        val name = "\"${need.title}\" (${need.language})"
        val language = LANGUAGES[need.language.lowercase()]
        if (language == null) {
            report(url, token, need, BridgeSubtitleProtocol.STATUS_FAILED, "idioma no soportado por el puente")
            log.line("Subtítulos $name: idioma no soportado.")
            return Outcome.FAILED
        }
        val claimed = api.postSubtitleStatus(url, token, status(need, BridgeSubtitleProtocol.STATUS_SEARCHING))
        if (claimed is ApiResult.Failure) {
            log.line("Subtítulos $name: la TV no acepta la búsqueda (${describe(claimed.failure)}); se omite.")
            return Outcome.SKIPPED
        }
        val request = SubtitleRequest(language = language, moviehash = need.movieHash, title = need.title)
        return when (val search = searcher.find(request)) {
            is SubtitleSearch.Found -> {
                val subtitle = search.subtitle
                val uploaded =
                    api.uploadSubtitle(url, token, need.torrentId, need.language, subtitle.label, subtitle.utf8Bytes())
                when (uploaded) {
                    is ApiResult.Success -> {
                        log.line(
                            "Subtítulos $name: subido ${uploaded.value.path}${subtitle.label?.let { " ($it)" } ?: ""}.",
                        )
                        Outcome.UPLOADED
                    }

                    is ApiResult.Failure -> {
                        val why = "la TV no ha aceptado el fichero (${describe(uploaded.failure)})"
                        report(url, token, need, BridgeSubtitleProtocol.STATUS_FAILED, why)
                        log.line("Subtítulos $name: $why.")
                        Outcome.FAILED
                    }
                }
            }

            is SubtitleSearch.NotFound -> {
                report(url, token, need, BridgeSubtitleProtocol.STATUS_NOT_FOUND, null)
                log.line("Subtítulos $name: no hay ninguno en OpenSubtitles.")
                Outcome.NOT_FOUND
            }

            is SubtitleSearch.QuotaExhausted -> {
                report(url, token, need, BridgeSubtitleProtocol.STATUS_FAILED, QUOTA_MESSAGE)
                log.line("Subtítulos $name: $QUOTA_MESSAGE.")
                Outcome.QUOTA
            }

            is SubtitleSearch.Failed -> {
                val why = describe(search.failure)
                report(url, token, need, BridgeSubtitleProtocol.STATUS_FAILED, why)
                log.line("Subtítulos $name: $why.")
                when (search.failure) {
                    OsFailure.LoginRefused, OsFailure.Unauthorized, OsFailure.RateLimited -> Outcome.OPENSUBTITLES
                    is OsFailure.Http, is OsFailure.Network -> Outcome.FAILED
                }
            }
        }
    }

    private suspend fun report(
        url: String,
        token: String,
        need: SubtitleNeedDto,
        status: String,
        message: String?,
    ) {
        val posted = api.postSubtitleStatus(url, token, status(need, status, message))
        if (posted is ApiResult.Failure) {
            log.line("Subtítulos: la TV no acepta el estado $status (${describe(posted.failure)}).")
        }
    }

    private fun quotaExhausted(): Boolean = searcher.quota().isExhausted(now())

    private fun status(
        need: SubtitleNeedDto,
        status: String,
        message: String? = null,
    ) = SubtitleStatusDto(torrentId = need.torrentId, language = need.language, status = status, message = message)

    private fun describe(failure: ApiFailure): String =
        when (failure) {
            ApiFailure.Unauthorized -> "401"
            is ApiFailure.Http -> "${failure.status}${failure.code?.let { " $it" } ?: ""}"
            is ApiFailure.Network -> failure.reason
        }

    /** Goes to the TV as the `failed` message: never a credential, the JWT or a response body. */
    private fun describe(failure: OsFailure): String =
        when (failure) {
            OsFailure.LoginRefused -> "OpenSubtitles rechaza el usuario o la contraseña"
            OsFailure.Unauthorized -> "OpenSubtitles rechaza la clave de API"
            OsFailure.RateLimited -> "OpenSubtitles pide esperar (demasiadas peticiones)"
            is OsFailure.Http -> "OpenSubtitles responde ${failure.status}"
            is OsFailure.Network -> "OpenSubtitles no responde (${failure.reason})"
        }

    companion object {
        /** ADR-0005 §5 / #282: a pass every 30 minutes besides the connect and the nudges. */
        val DEFAULT_INTERVAL: Duration = 30.minutes

        const val CONNECT: String = "conexión"
        const val NUDGE: String = "aviso de la TV"
        const val TIMER: String = "temporizador"

        private const val QUOTA_MESSAGE = "cuota diaria de OpenSubtitles agotada"

        /** The TV's two-letter tags (#280) for the two languages the bridge fetches. */
        private val LANGUAGES = mapOf("en" to SubtitleLanguage.ENGLISH, "es" to SubtitleLanguage.SPANISH)
    }
}
