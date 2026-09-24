package com.teachermovies.tv

import android.app.Application
import com.teachermovies.tv.di.AppContainer

/**
 * Process entry point: builds the one [AppContainer] every other object gets its collaborators
 * from (ADR-0003). There is no DI framework, so this is where the object graph is rooted.
 *
 * It also starts the embedded HTTP server (#66), so a phone on the LAN can reach the TV whenever
 * the process runs; the foreground `TorrentService` that `MainActivity` starts keeps it running.
 */
class TeacherMoviesApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.httpServerController.start()
        // Announces the server on the LAN while it runs (#103); never on the server's path.
        container.serverAnnouncementCoordinator.start()
    }
}
