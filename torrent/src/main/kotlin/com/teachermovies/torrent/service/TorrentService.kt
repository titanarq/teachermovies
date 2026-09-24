package com.teachermovies.torrent.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.teachermovies.torrent.R
import com.teachermovies.torrent.api.EngineResult
import com.teachermovies.torrent.api.TorrentEngine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The foreground service that keeps downloads running while the player or another app is in
 * front. It owns the engine's lifecycle: `start()` when created, `saveResumeData()` + `stop()`
 * when destroyed. The engine itself comes from [TorrentEngineHolder], which the application
 * initialises before calling [start] (#55).
 *
 * Android 14+ / targetSdk 35 rules it follows:
 * - its type, `dataSync`, is declared in the manifest, passed to `startForeground`, and backed by
 *   the `FOREGROUND_SERVICE_DATA_SYNC` permission;
 * - `startForeground` is the first thing `onCreate` does, well inside the window the system gives
 *   a service started with `startForegroundService`;
 * - a `dataSync` service must be started while the app is visible (not from `BOOT_COMPLETED` on
 *   Android 15), so [start] belongs to the UI's startup path;
 * - Android 15 limits `dataSync` to 6 h per 24 h and then calls [onTimeout]; the service stops
 *   itself there (saving resume data) instead of being killed with an ANR.
 */
class TorrentService : LifecycleService() {
    private var engine: TorrentEngine? = null

    override fun onCreate() {
        super.onCreate()
        val notifications = NotificationManagerCompat.from(this)
        notifications.createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.torrent_notification_channel_name))
                .setShowBadge(false)
                .build(),
        )
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(NotificationText.NO_ACTIVE_DOWNLOADS),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )

        if (!TorrentEngineHolder.isInitialized) {
            Log.e(TAG, "TorrentEngineHolder.init(engine) was not called before starting the service")
            stopSelf()
            return
        }
        val engine = TorrentEngineHolder.engine
        this.engine = engine
        TorrentEngineHolder.lifecycleScope.launch {
            val result = engine.start()
            if (result is EngineResult.Failure) {
                Log.e(TAG, "Torrent engine failed to start: ${result.error}")
            }
        }
        lifecycleScope.launch {
            engine.torrents
                .map(NotificationText::format)
                .distinctUntilChanged()
                .throttleLatest(NOTIFICATION_PERIOD_MILLIS)
                .collect { text ->
                    // Without POST_NOTIFICATIONS (Android 13+) the notification is simply hidden.
                    if (notifications.areNotificationsEnabled()) {
                        @Suppress("MissingPermission")
                        notifications.notify(NOTIFICATION_ID, notification(text))
                    }
                }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    /** Android 15's `dataSync` time limit (6 h per 24 h) was reached: stop cleanly. */
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        Log.w(TAG, "Foreground service time limit reached (type $fgsType); stopping")
        stopSelf()
    }

    override fun onDestroy() {
        engine?.let { engine ->
            TorrentEngineHolder.lifecycleScope.launch {
                val saved = engine.saveResumeData()
                if (saved is EngineResult.Failure) {
                    Log.e(TAG, "Saving resume data failed: ${saved.error}")
                }
                engine.stop()
            }
        }
        engine = null
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val launch =
            packageManager.getLeanbackLaunchIntentForPackage(packageName)
                ?: packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent =
            launch?.let {
                PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
            }
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.torrent_notification_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val CHANNEL_ID: String = "downloads"
        internal const val NOTIFICATION_ID: Int = 54_001
        internal const val NOTIFICATION_PERIOD_MILLIS: Long = 2_000
        private const val TAG = "TorrentService"

        /**
         * Starts (or keeps) the service in the foreground. Call it while the app is visible:
         * Android 12+ throws `ForegroundServiceStartNotAllowedException` for a background start.
         */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TorrentService::class.java))
        }

        /** Stops the service, which saves resume data and stops the engine. */
        fun stop(context: Context) {
            context.stopService(Intent(context, TorrentService::class.java))
        }
    }
}
