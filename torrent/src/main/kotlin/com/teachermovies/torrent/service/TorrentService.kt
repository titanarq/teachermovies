package com.teachermovies.torrent.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import com.teachermovies.torrent.api.TorrentSnapshot
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The notification's two halves (#250), both read from the same snapshot list: [downloading] picks
 * which title string is rendered and [text] is [NotificationText.format]'s line, so the title can
 * never claim a download the text denies. The service re-posts only when this value changes.
 */
private data class NotificationContent(
    val downloading: Boolean,
    val text: String,
)

/** Both halves out of one [snapshots] value, so they describe the same instant of the engine. */
private fun notificationContent(snapshots: List<TorrentSnapshot>): NotificationContent =
    NotificationContent(
        downloading = NotificationText.hasActiveDownloads(snapshots),
        text = NotificationText.format(snapshots),
    )

/**
 * The foreground service that keeps downloads running while the player or another app is in
 * front. It owns the engine's lifecycle: `start()` when created, `saveResumeData()` + `stop()`
 * when destroyed. The engine itself comes from [TorrentEngineHolder], which the application
 * initialises before calling [start] (#55).
 *
 * Android 14+ / targetSdk 35 rules it follows:
 * - its type comes from [ForegroundServiceTypes.forSdk], is declared in the manifest, is passed to
 *   `startForeground`, and is backed by the matching per-type permission: `specialUse` on API 34+,
 *   `dataSync` on API 29..33, no type below 29 (#129);
 * - `startForeground` is the first thing `onCreate` does, well inside the window the system gives
 *   a service started with `startForegroundService`;
 * - `specialUse` is what keeps downloads alive on Android 15, which caps `dataSync` at 6 h per
 *   24 h and refuses to start a `dataSync` service from `BOOT_COMPLETED` (#126); the justification
 *   the system and Play ask for is the manifest's `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`;
 * - on API 29..33, where the service still runs as `dataSync`, Android 15's cap can be reached and
 *   then calls [onTimeout]: the service stops itself there (saving resume data) instead of being
 *   killed with an ANR.
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
            notification(NotificationContent(downloading = false, text = NotificationText.NO_ACTIVE_DOWNLOADS)),
            ForegroundServiceTypes.forSdk(Build.VERSION.SDK_INT),
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
                .map(::notificationContent)
                .distinctUntilChanged()
                .throttleLatest(NOTIFICATION_PERIOD_MILLIS)
                .collect { content ->
                    // Without POST_NOTIFICATIONS (Android 13+) the notification is simply hidden.
                    if (notifications.areNotificationsEnabled()) {
                        @Suppress("MissingPermission")
                        notifications.notify(NOTIFICATION_ID, notification(content))
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

    /**
     * The system's foreground-service time limit was reached: stop cleanly, saving resume data in
     * [onDestroy]. It still fires for the `dataSync` type on the API 29..33 devices that hit
     * Android 15's 6 h per 24 h cap -- on API 34+ [ForegroundServiceTypes] runs the service as
     * `specialUse`, which that cap does not apply to -- and for any future limit on either type.
     */
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

    private fun notification(content: NotificationContent): Notification {
        val title =
            if (content.downloading) {
                R.string.torrent_notification_title_downloading
            } else {
                R.string.torrent_notification_title_idle
            }
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
            .setContentTitle(getString(title))
            .setContentText(content.text)
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
