package com.teachermovies.torrent.service

import com.teachermovies.core.model.DownloadState
import com.teachermovies.torrent.api.TorrentSnapshot
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * The text of the foreground download notification, e.g. `2 descargas · 45 % · 8,3 MB/s` or
 * `Sin descargas activas`, plus [hasActiveDownloads], which picks its title (#250). Pure, so both
 * are covered by a JVM test; [TorrentService] only renders them, and asks for both from the same
 * snapshot list so the title can never claim a download the text denies.
 *
 * A torrent is *active* unless it is [DownloadState.Completed] or [DownloadState.Error]. The
 * percentage is the active torrents' combined progress (downloaded over total bytes, rounded down
 * so it never shows 100 % before everything is done), falling back to the mean of
 * [TorrentSnapshot.progressPercent] while no active torrent knows its size yet. The speed is the
 * sum of the active download rates, in decimal units (1 MB = 1 000 000 B) with one decimal and the
 * Spanish decimal comma.
 */
object NotificationText {
    const val NO_ACTIVE_DOWNLOADS: String = "Sin descargas activas"

    private const val SEPARATOR = " · "
    private val SPANISH = DecimalFormatSymbols(Locale.forLanguageTag("es-ES"))
    private val INACTIVE = setOf(DownloadState.Completed, DownloadState.Error)
    private val UNITS = listOf("B/s", "kB/s", "MB/s", "GB/s")
    private val THOUSAND = BigDecimal(1_000)

    /**
     * Whether anything is still downloading, which is what picks the notification's title (#250).
     * False exactly when [format] returns [NO_ACTIVE_DOWNLOADS], since both apply [INACTIVE], so no
     * state renders a title that claims a download next to a text that denies one.
     */
    fun hasActiveDownloads(snapshots: List<TorrentSnapshot>): Boolean = snapshots.any { it.state !in INACTIVE }

    fun format(snapshots: List<TorrentSnapshot>): String {
        val active = snapshots.filter { it.state !in INACTIVE }
        if (active.isEmpty()) return NO_ACTIVE_DOWNLOADS
        val count = if (active.size == 1) "1 descarga" else "${active.size} descargas"
        val rate = active.sumOf { it.downloadRateBps.coerceAtLeast(0) }
        return count + SEPARATOR + "${percent(active)} %" + SEPARATOR + speed(rate)
    }

    private fun percent(active: List<TorrentSnapshot>): Int {
        val sized = active.filter { it.totalBytes > 0 }
        val value =
            if (sized.isNotEmpty()) {
                val done = sized.sumOf { it.downloadedBytes.coerceIn(0, it.totalBytes) }
                val total = sized.sumOf { it.totalBytes }
                done * 100.0 / total
            } else {
                active.map { it.progressPercent }.average()
            }
        return value.toInt().coerceIn(0, 100)
    }

    /** `8_300_000` -> `"8,3 MB/s"`; below 1 kB/s whole bytes, e.g. `"512 B/s"`. */
    internal fun speed(bytesPerSecond: Long): String {
        var value = BigDecimal(bytesPerSecond.coerceAtLeast(0))
        var unit = 0
        while (value >= THOUSAND && unit < UNITS.lastIndex) {
            value = value.divide(THOUSAND)
            unit++
        }
        val formatter = DecimalFormat(if (unit == 0) "0" else "0.0", SPANISH)
        formatter.roundingMode = RoundingMode.HALF_UP
        formatter.isGroupingUsed = false
        return "${formatter.format(value)} ${UNITS[unit]}"
    }
}
