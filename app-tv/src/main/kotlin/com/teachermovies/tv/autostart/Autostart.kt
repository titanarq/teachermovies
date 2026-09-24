package com.teachermovies.tv.autostart

/**
 * What the app starts by itself after the TV boots (#126): the foreground torrent service and the
 * embedded HTTP server, through the entry points the app already uses. The production
 * implementation is [ServiceAutostart]; tests use a recording one.
 */
interface Autostart {
    /** Starts everything autostart covers; a part the platform refuses is reported, not thrown. */
    fun start(): AutostartResult
}

/** Outcome of [Autostart.start]. */
sealed interface AutostartResult {
    /** Everything was started (or was already running). */
    data object Started : AutostartResult

    /**
     * The HTTP server was started but the platform refused to start the foreground torrent
     * service from the boot broadcast (for example Android 15's `dataSync` boot restriction).
     */
    data class TorrentServiceRefused(val reason: String) : AutostartResult
}
