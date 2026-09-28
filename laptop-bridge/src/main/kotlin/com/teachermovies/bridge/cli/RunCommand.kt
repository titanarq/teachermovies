package com.teachermovies.bridge.cli

import com.teachermovies.bridge.config.BridgeConfigStore
import com.teachermovies.bridge.config.ConfigLoad
import com.teachermovies.bridge.logs.LogStreamSource
import com.teachermovies.bridge.logs.MirrorCursorStore
import com.teachermovies.bridge.logs.TvLogFiles
import com.teachermovies.bridge.logs.TvLogMirror
import com.teachermovies.bridge.opensubtitles.CredentialsFile
import com.teachermovies.bridge.opensubtitles.CredentialsLoad
import com.teachermovies.bridge.opensubtitles.OpenSubtitlesApi
import com.teachermovies.bridge.opensubtitles.SubtitleFinder
import com.teachermovies.bridge.run.JobHandlerRegistry
import com.teachermovies.bridge.run.RunLog
import com.teachermovies.bridge.run.RunLoop
import com.teachermovies.bridge.run.TvDiscovery
import com.teachermovies.bridge.subtitles.SubtitleFetchLoop
import com.teachermovies.bridge.subtitles.SubtitleTrigger
import com.teachermovies.bridge.tv.TvApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * `teachermovies-bridge run` (#277): the long-lived process the systemd user unit of #278 keeps up.
 * Checks there is a pairing, then hands over to [RunLoop], which logs to [out] and to
 * `bridge.log` next to the config file, and alongside it to [TvLogMirror] (#272), which copies the
 * TV log into dated files in [logsDir], and -- when [credentials] loads -- [SubtitleFetchLoop] (#282)
 * over OpenSubtitles through [httpClient]. The jobs are answered by what [jobHandlers] builds (#291:
 * explain over Claude), whose Claude conversations are warmed up at once and closed when `run`
 * ends. It returns -- [ExitCode.FAILED] -- only when there is no
 * usable pairing or the TV refuses the token; otherwise it runs until the process is stopped.
 */
internal class RunCommand(
    private val out: Appendable,
    private val err: Appendable,
    private val store: BridgeConfigStore,
    private val api: TvApi,
    private val jobHandlers: (RunLog) -> JobHandlers,
    private val discovery: TvDiscovery,
    private val logsDir: Path,
    private val credentials: CredentialsFile,
    private val httpClient: HttpClient,
) {
    suspend fun run(): Int {
        val config =
            when (val load = store.load()) {
                ConfigLoad.Missing -> {
                    return fail("No hay ninguna configuración: no existe ${store.path}. $PAIR_HINT")
                }

                is ConfigLoad.Loaded -> {
                    load.config
                }

                is ConfigLoad.Corrupt -> {
                    return fail("La configuración ${store.path} no se puede leer (${load.reason}). $PAIR_HINT")
                }

                is ConfigLoad.Unreadable -> {
                    return fail("La configuración ${store.path} no se puede leer (${load.reason}). $PAIR_HINT")
                }
            }
        if (!config.isPaired) return fail("Este portátil no está emparejado con ninguna TV. $PAIR_HINT")
        val log = RunLog(out, store.path.resolveSibling(RunLog.FILE_NAME))
        val token = checkNotNull(config.token)
        val handlers = jobHandlers(log)
        val end =
            try {
                coroutineScope {
                    val warmUps = handlers.conversations.map { conversation -> launch { conversation.warmUp() } }
                    val mirror =
                        launch {
                            TvLogMirror(
                                LogStreamSource.of(api),
                                TvLogFiles(logsDir),
                                MirrorCursorStore(logsDir.resolve(MirrorCursorStore.FILE_NAME)),
                                log,
                            ).run({ currentTvUrl(config.tvUrl) }, token)
                        }
                    val fetchLoop = subtitleLoop(log)
                    val fetcher = fetchLoop?.let { launch { it.run({ currentTvUrl(config.tvUrl) }, token) } }
                    val registry = JobHandlerRegistry(handlers.handlers)
                    RunLoop(api, store, registry, log, discovery, subtitles = fetchLoop ?: SubtitleTrigger.NONE)
                        .run(config)
                        .also {
                            mirror.cancel()
                            fetcher?.cancel()
                            warmUps.forEach { it.cancel() }
                        }
                }
            } finally {
                withContext(NonCancellable) { handlers.conversations.forEach { it.close() } }
            }
        return when (end) {
            RunLoop.End.Unauthorized -> fail("La TV ya no acepta el token de este portátil. $PAIR_HINT")
        }
    }

    /**
     * The fetch loop over OpenSubtitles, or null -- said once in the log -- when the credentials file
     * is not usable: the rest of `run` goes on without automatic subtitles. The file is read once,
     * so one fixed or added later takes a restart of the service.
     */
    private fun subtitleLoop(log: RunLog): SubtitleFetchLoop? {
        val loaded =
            when (val load = credentials.load()) {
                is CredentialsLoad.Loaded -> {
                    load.credentials
                }

                CredentialsLoad.Missing -> {
                    log.line("Sin credenciales de OpenSubtitles en ${credentials.path}: no se buscan subtítulos.")
                    return null
                }

                is CredentialsLoad.Incomplete -> {
                    log.line(
                        "Faltan claves en ${credentials.path} (${load.missingKeys.joinToString()}): " +
                            "no se buscan subtítulos.",
                    )
                    return null
                }

                is CredentialsLoad.Unreadable -> {
                    log.line("No se puede leer ${credentials.path} (${load.reason}): no se buscan subtítulos.")
                    return null
                }
            }
        return SubtitleFetchLoop(api, SubtitleFinder(OpenSubtitlesApi(httpClient, loaded)), log)
    }

    /** The URL the job loop last saved -- it moves after an mDNS re-discovery -- or [fallback]. */
    private fun currentTvUrl(fallback: String?): String {
        val saved = (store.load() as? ConfigLoad.Loaded)?.config?.tvUrl
        return checkNotNull(saved ?: fallback)
    }

    private fun fail(message: String): Int {
        err.appendLine(message)
        return ExitCode.FAILED
    }

    private companion object {
        const val PAIR_HINT =
            "Empareja con: teachermovies-bridge pair --url http://<ip-de-la-tv>:8787 --pin <pin>"
    }
}
