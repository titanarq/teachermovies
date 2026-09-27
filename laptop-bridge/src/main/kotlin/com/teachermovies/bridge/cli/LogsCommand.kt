package com.teachermovies.bridge.cli

import com.teachermovies.bridge.config.BridgeConfigStore
import com.teachermovies.bridge.config.ConfigLoad
import com.teachermovies.bridge.protocol.LogEntryDto
import com.teachermovies.bridge.protocol.LogsPageDto
import com.teachermovies.bridge.tv.ApiResult
import com.teachermovies.bridge.tv.TvApi
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `teachermovies-bridge logs` (#271): prints one page of the TV's redacted log ring buffer
 * (ADR-0006 §4) to stdout, so a laptop can diagnose the TV without `adb` and without the phone web
 * UI (#273). `--since`, `--level` and `--limit` are passed through as the route's query, and the
 * last `seq` printed is the cursor a following `--since` continues from.
 *
 * This is a one-shot snapshot; mirroring the stream to a local file is #272. The TV's own lines are
 * already redacted, and the token this command authenticates with is read from the config and never
 * printed.
 */
internal class LogsCommand(
    private val out: Appendable,
    private val err: Appendable,
    private val store: BridgeConfigStore,
    private val api: TvApi,
) {
    suspend fun run(command: Command.Logs): Int {
        val pairing = pairedTv() ?: return ExitCode.FAILED
        val result =
            api.logs(
                baseUrl = pairing.tvUrl,
                token = pairing.token,
                since = command.since,
                level = command.level,
                limit = command.limit,
            )
        return when (result) {
            is ApiResult.Success -> printPage(result.value)
            is ApiResult.Failure -> fail("No se ha podido leer el registro: ${describeFailure(result.failure)}.")
        }
    }

    private fun printPage(page: LogsPageDto): Int {
        val boot = page.bootId.take(BOOT_ID_LENGTH)
        if (page.entries.isEmpty()) {
            out.appendLine("La TV no tiene líneas con ese filtro (arranque $boot).")
            return ExitCode.OK
        }
        page.entries.forEach { out.appendLine(line(it)) }
        val last = page.entries.last()
        out.appendLine("${page.entries.size} líneas (arranque $boot). Continúa con: --since ${last.seq}")
        return ExitCode.OK
    }

    /**
     * One entry as `fecha hora NIVEL [módulo] mensaje`, in this machine's time zone: the TV sends
     * epoch milliseconds and a log nobody can read is not a log.
     */
    private fun line(entry: LogEntryDto): String {
        val time = TIME_FORMAT.format(Instant.ofEpochMilli(entry.timeMs).atZone(ZoneId.systemDefault()))
        return "$time ${entry.level.uppercase().padEnd(LEVEL_WIDTH)} [${entry.module}] ${entry.message}"
    }

    /** The stored TV URL and token, or null once [err] says why there are none to use. */
    private fun pairedTv(): PairedTv? {
        val config =
            when (val load = store.load()) {
                ConfigLoad.Missing -> {
                    report("No hay ninguna configuración: no existe ${store.path}. $PAIR_HINT")
                    return null
                }

                is ConfigLoad.Loaded -> {
                    load.config
                }

                is ConfigLoad.Corrupt -> {
                    report("La configuración ${store.path} no se puede leer (${load.reason}). $PAIR_HINT")
                    return null
                }

                is ConfigLoad.Unreadable -> {
                    report("La configuración ${store.path} no se puede leer (${load.reason}). $PAIR_HINT")
                    return null
                }
            }
        val tvUrl = config.tvUrl
        val token = config.token
        if (tvUrl == null || token == null) {
            report("Este portátil no está emparejado con ninguna TV. $PAIR_HINT")
            return null
        }
        return PairedTv(tvUrl, token)
    }

    private fun report(message: String) {
        err.appendLine(message)
    }

    private fun fail(message: String): Int {
        report(message)
        return ExitCode.FAILED
    }

    /** The two things `GET /api/logs` needs, already known to be present. */
    private data class PairedTv(
        val tvUrl: String,
        val token: String,
    ) {
        override fun toString(): String = "PairedTv(tvUrl=$tvUrl, token=<redacted>)"
    }

    private companion object {
        const val BOOT_ID_LENGTH = 8
        const val LEVEL_WIDTH = 5

        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

        const val PAIR_HINT =
            "Empareja con: teachermovies-bridge pair --url http://<ip-de-la-tv>:8787 --pin <pin>"
    }
}
