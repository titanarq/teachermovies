package com.teachermovies.tv.autostart

import android.content.Context
import com.teachermovies.http.HttpServerController
import com.teachermovies.torrent.service.TorrentService

/**
 * Production [Autostart]: the same entry points the app uses when it is opened by hand --
 * [HttpServerController.start] (#66, idempotent: `TeacherMoviesApp.onCreate` has usually called it
 * already by the time the boot broadcast arrives) and [TorrentService.start] (#55), which calls
 * `startForegroundService`. Nothing of the torrent or HTTP logic lives here.
 *
 * The HTTP server goes first, so it runs even when the platform refuses the foreground service
 * start from the boot broadcast; that refusal (`ForegroundServiceStartNotAllowedException`, an
 * `IllegalStateException`) is returned as [AutostartResult.TorrentServiceRefused], never thrown
 * into the receiver.
 */
class ServiceAutostart(
    private val context: Context,
    private val httpServerController: HttpServerController,
) : Autostart {
    override fun start(): AutostartResult {
        httpServerController.start()
        return try {
            TorrentService.start(context)
            AutostartResult.Started
        } catch (e: IllegalStateException) {
            AutostartResult.TorrentServiceRefused(e.message ?: e.javaClass.name)
        }
    }
}
