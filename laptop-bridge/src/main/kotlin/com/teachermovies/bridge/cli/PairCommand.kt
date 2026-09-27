package com.teachermovies.bridge.cli

import com.teachermovies.bridge.config.BridgeConfig
import com.teachermovies.bridge.config.BridgeConfigStore
import com.teachermovies.bridge.config.ConfigSave
import com.teachermovies.bridge.tv.PairOutcome
import com.teachermovies.bridge.tv.TvApi
import java.net.InetAddress
import java.nio.file.Path

/**
 * `teachermovies-bridge pair` (#271): asks the TV for a bridge-scoped token (ADR-0005 §1) and
 * stores it, with the TV's URL, in the config file [BridgeConfigStore] keeps at mode 0600.
 *
 * The PIN goes to the TV and nowhere else; the token lands in that file and is never printed. A
 * token the TV issued with a scope other than `bridge` is refused rather than stored, because a
 * phone token reaches neither `/api/bridge` nor `/api/logs` (ADR-0005 §4) and storing one would make
 * every later command fail with a 401 that looks like a revoked pairing.
 */
internal class PairCommand(
    private val out: Appendable,
    private val err: Appendable,
    private val store: BridgeConfigStore,
    private val api: TvApi,
) {
    suspend fun run(command: Command.Pair): Int {
        val deviceName = command.deviceName ?: defaultDeviceName()
        return when (val outcome = api.pair(command.url, command.pin, deviceName)) {
            is PairOutcome.Paired -> storePairing(command.url, outcome.token, deviceName)
            PairOutcome.WrongPin -> fail("PIN incorrecto: la TV no lo ha aceptado.")
            PairOutcome.TooManyAttempts -> fail(TOO_MANY_ATTEMPTS)
            is PairOutcome.WrongScope -> fail(wrongScopeText(outcome.issued))
            is PairOutcome.Failed -> fail("Emparejamiento fallido: ${describeFailure(outcome.failure)}.")
        }
    }

    private fun storePairing(
        tvUrl: String,
        token: String,
        deviceName: String,
    ): Int {
        val config = BridgeConfig(tvUrl = tvUrl, token = token, deviceName = deviceName)
        return when (val saved = store.save(config)) {
            is ConfigSave.Saved -> paired(tvUrl, saved.path)
            is ConfigSave.Failed -> fail(saveFailedText(saved.reason))
        }
    }

    private fun paired(
        tvUrl: String,
        path: Path,
    ): Int {
        val permissions = store.permissions() ?: "sin determinar"
        out.appendLine("Emparejado con $tvUrl (ámbito '${TvApi.BRIDGE_SCOPE}').")
        out.appendLine("Configuración: $path (permisos $permissions; el token se guarda ahí y no se imprime).")
        out.appendLine("Comprueba la conexión con: teachermovies-bridge doctor")
        return ExitCode.OK
    }

    /** The host name, or a fixed fallback: the TV only ever shows this to the human. */
    private fun defaultDeviceName(): String {
        val hostName = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
        return hostName?.takeIf { it.isNotBlank() } ?: FALLBACK_DEVICE_NAME
    }

    private fun fail(message: String): Int {
        err.appendLine(message)
        return ExitCode.FAILED
    }

    private fun wrongScopeText(issued: String?): String =
        "La TV ha emitido un token de ámbito '${issued ?: "ninguno"}' en vez de '${TvApi.BRIDGE_SCOPE}': " +
            "su software es anterior a los tokens por ámbito. No se ha guardado nada; " +
            "actualiza la TV y vuelve a emparejar."

    private fun saveFailedText(reason: String): String =
        "Emparejamiento correcto, pero no se ha podido guardar la configuración ($reason): " +
            "el token se ha descartado. Resuélvelo y vuelve a emparejar."

    private companion object {
        const val FALLBACK_DEVICE_NAME = "teachermovies-bridge"

        const val TOO_MANY_ATTEMPTS =
            "Demasiados intentos fallidos: la TV no acepta más PINes por ahora. " +
                "Espera un minuto y vuelve a intentarlo."
    }
}
