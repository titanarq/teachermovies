package com.teachermovies.tv.autostart

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.teachermovies.tv.TeacherMoviesApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives `BOOT_COMPLETED` (#126) and hands the action to `AppContainer.bootAutostartCoordinator`,
 * which reads the setting and decides; any other action is ignored before anything is read.
 *
 * The broadcast is kept open with [goAsync] while the setting is read and finished in every path,
 * failure included. The app is not brought to the foreground: Android 10+ does not let a
 * background receiver start an activity.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action
        if (action != BootAutostartCoordinator.ACTION_BOOT_COMPLETED) return
        val coordinator = (context.applicationContext as TeacherMoviesApp).container.bootAutostartCoordinator
        val pending = goAsync()
        scope.launch {
            try {
                when (val decision = coordinator.onBroadcast(action)) {
                    is BootDecision.Started ->
                        when (val result = decision.result) {
                            AutostartResult.Started -> Log.i(TAG, "Autostart after boot: services started")
                            is AutostartResult.TorrentServiceRefused ->
                                Log.w(TAG, "Autostart after boot: torrent service refused: ${result.reason}")
                        }
                    BootDecision.Disabled -> Log.i(TAG, "Autostart after boot is off")
                    BootDecision.IgnoredAction -> Unit
                }
            } catch (e: Exception) {
                Log.e(TAG, "Autostart after boot failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootCompletedReceiver"

        // Main: `ServiceAutostart` starts a service and the server controller, both main-safe calls;
        // the settings read suspends without blocking it.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
