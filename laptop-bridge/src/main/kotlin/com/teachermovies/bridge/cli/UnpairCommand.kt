package com.teachermovies.bridge.cli

import com.teachermovies.bridge.config.BridgeConfigStore
import com.teachermovies.bridge.config.ConfigDelete
import com.teachermovies.bridge.config.ConfigLoad
import java.nio.file.Path

/**
 * `teachermovies-bridge unpair` (#271): forgets the pairing by deleting the config file, the only
 * place the bridge keeps the token.
 *
 * The TV keeps its own hash of that token until somebody chooses "Olvidar portátil" on the TV itself
 * (ADR-0005 §4); this command deliberately does not call the TV, so it also works with the TV off or
 * unreachable, which is exactly when a pairing needs undoing. A config that cannot be read is
 * deleted all the same -- "forget" means the file goes -- and the report says why it was unusable.
 */
internal class UnpairCommand(
    private val out: Appendable,
    private val err: Appendable,
    private val store: BridgeConfigStore,
) {
    fun run(): Int {
        val unusable = unusableReason()
        return when (val deleted = store.delete()) {
            is ConfigDelete.Deleted -> deleted(deleted.path, unusable)
            is ConfigDelete.Absent -> absent(deleted.path)
            is ConfigDelete.Failed -> fail("No se ha podido borrar ${store.path} (${deleted.reason}).")
        }
    }

    private fun deleted(
        path: Path,
        unusable: String?,
    ): Int {
        out.appendLine("Desemparejado: se ha borrado $path.")
        if (unusable != null) out.appendLine("El fichero no era legible ($unusable); se ha borrado de todos modos.")
        out.appendLine("La TV conserva su copia del token hasta que olvides este portátil desde la propia TV.")
        return ExitCode.OK
    }

    private fun absent(path: Path): Int {
        out.appendLine("No había ningún emparejamiento guardado: no existe $path.")
        return ExitCode.OK
    }

    /** Why the stored config could not be read, when it could not: [run] deletes it either way. */
    private fun unusableReason(): String? =
        when (val load = store.load()) {
            is ConfigLoad.Corrupt -> load.reason
            is ConfigLoad.Unreadable -> load.reason
            ConfigLoad.Missing -> null
            is ConfigLoad.Loaded -> null
        }

    private fun fail(message: String): Int {
        err.appendLine(message)
        return ExitCode.FAILED
    }
}
