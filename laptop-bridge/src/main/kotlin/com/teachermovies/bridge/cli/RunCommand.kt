package com.teachermovies.bridge.cli

import com.teachermovies.bridge.config.BridgeConfigStore
import com.teachermovies.bridge.config.ConfigLoad
import com.teachermovies.bridge.run.JobHandlerRegistry
import com.teachermovies.bridge.run.RunLog
import com.teachermovies.bridge.run.RunLoop
import com.teachermovies.bridge.run.TvDiscovery
import com.teachermovies.bridge.tv.TvApi

/**
 * `teachermovies-bridge run` (#277): the long-lived process the systemd user unit of #278 keeps up.
 * Checks there is a pairing, then hands over to [RunLoop], which logs to [out] and to
 * `bridge.log` next to the config file. It returns -- [ExitCode.FAILED] -- only when there is no
 * usable pairing or the TV refuses the token; otherwise it runs until the process is stopped.
 */
internal class RunCommand(
    private val out: Appendable,
    private val err: Appendable,
    private val store: BridgeConfigStore,
    private val api: TvApi,
    private val registry: JobHandlerRegistry,
    private val discovery: TvDiscovery,
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
        return when (RunLoop(api, store, registry, log, discovery).run(config)) {
            RunLoop.End.Unauthorized -> fail("La TV ya no acepta el token de este portátil. $PAIR_HINT")
        }
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
