package com.teachermovies.tv.ui.player

import kotlin.math.ceil
import kotlin.math.floor

/**
 * The real screen the player draws on (#347): the window in pixels and the [density] that turns dp
 * into pixels. TVs are 720p at least and usually 1080p, with a density that varies by device, so
 * everything the explanation panel sizes is derived from this and never from a fixed dp.
 */
data class ScreenMetrics(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
) {
    val widthDp: Float get() = widthPx / density
    val heightDp: Float get() = heightPx / density
}

/**
 * How an explanation is drawn in the panel (#347): [fontSp] and [lineHeightSp], and, when even the
 * smallest legible font does not hold the whole text, [ellipsis] with the [maxLines] that fit (the
 * rest is cut with "…"). The TV cannot scroll, so this is the whole answer to long texts.
 */
data class PanelFit(
    val fontSp: Float,
    val lineHeightSp: Float,
    val maxLines: Int,
    val ellipsis: Boolean,
)

/** Pure sizing of the explanation panel: no Compose, so it is unit-tested at 720p and 1080p. */
object ExplanationFit {
    /** The panel takes this much of the player's width and height (above the 60 % / 50 % floor). */
    const val WIDTH_FRACTION = 0.8f
    const val HEIGHT_FRACTION = 0.55f

    /** Inner padding of the panel on each side, in dp. */
    const val PADDING_DP = 24f

    /** Font bounds in pixels at 720p (scaled by the screen height): legible minimum and comfortable maximum. */
    const val MIN_FONT_PX_AT_720 = 22f
    const val MAX_FONT_PX_AT_720 = 36f

    private const val FONT_STEP_SP = 0.5f
    private const val LINE_FACTOR_AT_MAX = 1.4f
    private const val LINE_FACTOR_AT_MIN = 1.2f

    /** Average glyph width as a fraction of the font size; deliberately wide so the estimate errs on fitting. */
    private const val AVERAGE_GLYPH_EM = 0.55f

    fun panelWidthDp(screen: ScreenMetrics): Float = screen.widthDp * WIDTH_FRACTION

    fun panelHeightDp(screen: ScreenMetrics): Float = screen.heightDp * HEIGHT_FRACTION

    /** Smallest font, in sp, that stays legible on [screen]: [MIN_FONT_PX_AT_720] scaled by its height. */
    fun minFontSp(screen: ScreenMetrics): Float = MIN_FONT_PX_AT_720 * screen.heightPx / 720f / screen.density

    fun maxFontSp(screen: ScreenMetrics): Float = MAX_FONT_PX_AT_720 * screen.heightPx / 720f / screen.density

    /**
     * The largest font in [minFontSp]..[maxFontSp] (step 0.5 sp) at which [text] fits the panel of
     * [screen], with a line spacing that tightens from 1.4 to 1.2 times the font as it shrinks.
     * Lines are estimated per paragraph (`\n`) from the text length and the usable width. When even
     * the minimum does not fit, the minimum is used with [PanelFit.ellipsis] and as many lines as the
     * height holds.
     */
    fun fit(
        text: String,
        screen: ScreenMetrics,
    ): PanelFit {
        val min = minFontSp(screen)
        val max = maxOf(maxFontSp(screen), min)
        val usableWidth = panelWidthDp(screen) - 2 * PADDING_DP
        val usableHeight = panelHeightDp(screen) - 2 * PADDING_DP
        var font = max
        while (true) {
            val lineHeight = lineHeightFor(font, min, max)
            val lines = linesNeeded(text, font, usableWidth)
            if (lines * lineHeight <= usableHeight) return PanelFit(font, lineHeight, lines, ellipsis = false)
            if (font <= min) {
                val held = floor(usableHeight / lineHeight).toInt().coerceAtLeast(1)
                return PanelFit(min, lineHeight, held, ellipsis = true)
            }
            font = maxOf(font - FONT_STEP_SP, min)
        }
    }

    private fun lineHeightFor(
        font: Float,
        min: Float,
        max: Float,
    ): Float {
        val t = if (max > min) (font - min) / (max - min) else 0f
        return font * (LINE_FACTOR_AT_MIN + t * (LINE_FACTOR_AT_MAX - LINE_FACTOR_AT_MIN))
    }

    private fun linesNeeded(
        text: String,
        font: Float,
        usableWidthDp: Float,
    ): Int {
        val charsPerLine = (usableWidthDp / (font * AVERAGE_GLYPH_EM)).toInt().coerceAtLeast(1)
        return text.split('\n').sumOf { ceil(it.length / charsPerLine.toDouble()).toInt().coerceAtLeast(1) }
    }
}
