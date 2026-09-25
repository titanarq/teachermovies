package com.teachermovies.mobile

import android.app.Application
import com.teachermovies.mobile.di.MobileContainer

/** The phone app's `Application`: builds the one [MobileContainer] for the process (ADR-0003). */
class MobileApp : Application() {
    lateinit var container: MobileContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = MobileContainer(this)
    }
}
