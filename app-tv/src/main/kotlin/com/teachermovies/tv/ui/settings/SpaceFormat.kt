package com.teachermovies.tv.ui.settings

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

// Decimal gigabytes, as the TV's own storage settings count them.
private val BYTES_PER_GB = BigDecimal(1_000_000_000L)
private val SPANISH = DecimalFormatSymbols(Locale.forLanguageTag("es-ES"))

/**
 * The numbers of `X,Y GB libres de Z GB`: free space with one decimal and total space rounded to a
 * whole gigabyte, both with the Spanish decimal comma and no grouping separator. Pure, so the
 * screen's formatting is covered by a JVM test.
 */
internal object SpaceFormat {
    /** `5_050_000_000` -> `"5,1"`. */
    fun freeGb(bytes: Long): String = format(bytes, "0.0")

    /** `15_600_000_000` -> `"16"`. */
    fun totalGb(bytes: Long): String = format(bytes, "0")

    private fun format(
        bytes: Long,
        pattern: String,
    ): String {
        val formatter = DecimalFormat(pattern, SPANISH)
        formatter.roundingMode = RoundingMode.HALF_UP
        formatter.isGroupingUsed = false
        return formatter.format(BigDecimal(bytes.coerceAtLeast(0)).divide(BYTES_PER_GB))
    }
}
