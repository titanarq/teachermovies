package com.teachermovies.tv.autostart

import com.teachermovies.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/** What [BootAutostartCoordinator.onBroadcast] did with one broadcast. */
sealed interface BootDecision {
    /** The broadcast was not `BOOT_COMPLETED`: nothing was read and nothing was started. */
    data object IgnoredAction : BootDecision

    /** `autostartOnBoot` is off (the default): a boot changes nothing. */
    data object Disabled : BootDecision

    /** `autostartOnBoot` is on and [Autostart.start] ran once, with [result]. */
    data class Started(val result: AutostartResult) : BootDecision
}

/**
 * The boot decision, free of Android types: on a `BOOT_COMPLETED` action it reads the current
 * settings once and invokes [autostart] only when `autostartOnBoot` is true. The receiver does
 * nothing but hand it the intent's action (#126).
 */
class BootAutostartCoordinator(
    private val settings: SettingsRepository,
    private val autostart: Autostart,
) {
    suspend fun onBroadcast(action: String?): BootDecision {
        if (action != ACTION_BOOT_COMPLETED) return BootDecision.IgnoredAction
        if (!settings.settings.first().autostartOnBoot) return BootDecision.Disabled
        return BootDecision.Started(autostart.start())
    }

    companion object {
        /** `Intent.ACTION_BOOT_COMPLETED`, spelled out so this class stays a plain JVM class. */
        const val ACTION_BOOT_COMPLETED: String = "android.intent.action.BOOT_COMPLETED"
    }
}
