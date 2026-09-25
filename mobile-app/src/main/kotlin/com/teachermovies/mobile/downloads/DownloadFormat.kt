package com.teachermovies.mobile.downloads

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Every text of one download row (#198), formatted here and unit-tested, so no screen builds a
 * string of its own: progress with one decimal, the TV's snake_case state as its Spanish label,
 * speed in MB/s and downloaded / total in GB.
 *
 * Units are decimal (1000-based, not 1024-based) and the fixed ones the TV's own web UI shows, and
 * every number uses the Spanish decimal comma with no grouping separator, whatever the phone's
 * locale is. Pure Kotlin: no Android type, so it is tested on the JVM.
 */
object DownloadFormat {
    private val SPANISH = DecimalFormatSymbols(Locale.forLanguageTag("es-ES"))
    private const val BYTES_PER_MB = 1_000_000.0
    private const val BYTES_PER_GB = 1_000_000_000.0

    /** `72.44` -> `"72,4 %"`: one decimal, rounded half up, negatives clamped to zero. */
    fun progress(value: Double): String = "${oneDecimal(value)} %"

    /**
     * The label for one of the states `GET /api/torrents` reports
     * (`fetching_metadata`|`queued`|`downloading`|`paused`|`verifying`|`completed`|`error`). A value
     * outside that set -- a state a newer TV knows and this app does not -- is shown as it came
     * rather than guessed at.
     */
    fun stateLabel(state: String): String =
        when (state) {
            "fetching_metadata" -> "Obteniendo metadata"
            "queued" -> "Esperando"
            "downloading" -> "Descargando"
            "paused" -> "En pausa"
            "verifying" -> "Verificando"
            "completed" -> "Completado"
            "error" -> "Error"
            else -> state
        }

    /** `8_300_000` -> `"8,3 MB/s"`. */
    fun speed(bytesPerSecond: Long): String = "${oneDecimal(bytesPerSecond / BYTES_PER_MB)} MB/s"

    /**
     * `size(18_400_000_000, 25_600_000_000)` -> `"18,4 / 25,6 GB"`, both numbers always in GB;
     * before metadata arrives the total is unknown and reads `"1,5 / 0,0 GB"`.
     */
    fun size(
        downloadedBytes: Long,
        totalBytes: Long,
    ): String {
        val downloaded = oneDecimal(downloadedBytes / BYTES_PER_GB)
        val total = oneDecimal(totalBytes / BYTES_PER_GB)
        return "$downloaded / $total GB"
    }

    private fun oneDecimal(value: Double): String =
        spanishDecimal().format(BigDecimal.valueOf(value.coerceAtLeast(0.0)))

    private fun spanishDecimal(): DecimalFormat =
        DecimalFormat("0.0", SPANISH).apply {
            roundingMode = RoundingMode.HALF_UP
            isGroupingUsed = false
        }
}
