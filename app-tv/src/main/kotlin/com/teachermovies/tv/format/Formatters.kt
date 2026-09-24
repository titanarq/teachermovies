package com.teachermovies.tv.format

import com.teachermovies.core.model.DownloadState
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Every number and status the Descargas screen shows, formatted here and unit-tested, so no raw
 * byte count, rate or `DownloadState` name ever reaches Compose (#69).
 *
 * All formatting uses decimal (1000-based, not 1024-based) units and the Spanish locale: a comma
 * for the decimal separator, no grouping separator.
 */
object Formatters {
    private val SPANISH = DecimalFormatSymbols(Locale.forLanguageTag("es-ES"))
    private val UNITS = listOf("B", "KB", "MB", "GB", "TB")
    private const val UNIT_STEP = 1000L

    /** `1_500_000_000` -> `"1,5 GB"`. */
    fun bytes(value: Long): String {
        val unitIndex = unitIndexFor(value)
        return "${formatAtUnit(value, unitIndex)} ${UNITS[unitIndex]}"
    }

    /** `8_300_000` -> `"8,3 MB/s"`. */
    fun speed(bytesPerSecond: Long): String {
        val unitIndex = unitIndexFor(bytesPerSecond)
        return "${formatAtUnit(bytesPerSecond, unitIndex)} ${UNITS[unitIndex]}/s"
    }

    /**
     * `sizeText(18_400_000_000, 25_600_000_000)` -> `"18,4 / 25,6 GB"`: both numbers scaled to the
     * unit [totalBytes] falls into, so they always share one unit -- [downloadedBytes]'s unit is
     * used only while the total is not known yet (0 or less, before metadata arrives).
     */
    fun sizeText(
        downloadedBytes: Long,
        totalBytes: Long,
    ): String {
        val unitIndex = unitIndexFor(if (totalBytes > 0) totalBytes else downloadedBytes)
        return "${formatAtUnit(downloadedBytes, unitIndex)} / ${formatAtUnit(totalBytes, unitIndex)} ${UNITS[unitIndex]}"
    }

    /** `3725` -> `"1 h 2 min"`; `59` -> `"59 s"`; `null` -> `"—"` (no estimate yet). */
    fun eta(seconds: Long?): String {
        if (seconds == null) return "—"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            hours > 0 -> "$hours h $minutes min"
            minutes > 0 -> "$minutes min $secs s"
            else -> "$secs s"
        }
    }

    /**
     * Player clock (#78): `3_725_000` ms -> `"1:02:05"`, `65_000` -> `"1:05"`, `0` -> `"0:00"`;
     * truncated to the second, negatives clamped to zero.
     */
    fun playbackTime(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        val ss = secs.toString().padStart(2, '0')
        return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$ss" else "$minutes:$ss"
    }

    /** `72.44` -> `"72 %"`: rounded to the nearest whole percent. */
    fun percent(value: Double): String = "${value.roundToInt()} %"

    /** `0.456` -> `"0,46"`: two decimals, the Spanish decimal comma. */
    fun ratio(value: Double): String = decimalFormat("0.00").format(value)

    /** The user-visible label for [state], in Spanish. */
    fun stateLabel(state: DownloadState): String =
        when (state) {
            DownloadState.FetchingMetadata -> "Obteniendo metadata"
            DownloadState.Queued -> "En cola"
            DownloadState.Downloading -> "Descargando"
            DownloadState.Paused -> "En pausa"
            DownloadState.Verifying -> "Verificando"
            DownloadState.Completed -> "Completado"
            DownloadState.Error -> "Error"
        }

    /** Which of [UNITS] `value` falls into, dividing by [UNIT_STEP] until it is under it. */
    private fun unitIndexFor(value: Long): Int {
        var scaled = value.coerceAtLeast(0)
        var unitIndex = 0
        while (scaled >= UNIT_STEP && unitIndex < UNITS.lastIndex) {
            scaled /= UNIT_STEP
            unitIndex++
        }
        return unitIndex
    }

    /** [value] scaled to [unitIndex] and formatted: a bare integer for B, one decimal otherwise. */
    private fun formatAtUnit(
        value: Long,
        unitIndex: Int,
    ): String {
        val clamped = value.coerceAtLeast(0)
        if (unitIndex == 0) return clamped.toString()
        val divisor = BigDecimal.valueOf(UNIT_STEP).pow(unitIndex)
        val scaled = BigDecimal.valueOf(clamped).divide(divisor, 4, RoundingMode.HALF_UP)
        return decimalFormat("0.0").format(scaled)
    }

    private fun decimalFormat(pattern: String): DecimalFormat =
        DecimalFormat(pattern, SPANISH).apply {
            roundingMode = RoundingMode.HALF_UP
            isGroupingUsed = false
        }
}
