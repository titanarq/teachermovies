package com.teachermovies.tv

import android.app.Application
import com.teachermovies.tv.di.AppContainer

/**
 * Process entry point: builds the one [AppContainer] every other object gets its collaborators
 * from (ADR-0003). There is no DI framework, so this is where the object graph is rooted.
 */
class TeacherMoviesApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
