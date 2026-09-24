package com.teachermovies.tv.autostart

import com.teachermovies.core.settings.AppSettings
import com.teachermovies.core.settings.fake.InMemorySettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BootAutostartCoordinatorTest {
    private class RecordingAutostart(
        private val result: AutostartResult = AutostartResult.Started,
    ) : Autostart {
        var calls = 0
            private set

        override fun start(): AutostartResult {
            calls++
            return result
        }
    }

    private val boot = BootAutostartCoordinator.ACTION_BOOT_COMPLETED

    @Test
    fun settingOffNeverInvokesTheSeam() =
        runBlocking {
            val autostart = RecordingAutostart()
            val coordinator = BootAutostartCoordinator(InMemorySettingsRepository(), autostart)

            assertEquals(BootDecision.Disabled, coordinator.onBroadcast(boot))
            assertEquals(0, autostart.calls)
        }

    @Test
    fun settingOnInvokesTheSeamExactlyOnce() =
        runBlocking {
            val autostart = RecordingAutostart()
            val coordinator =
                BootAutostartCoordinator(InMemorySettingsRepository(AppSettings(autostartOnBoot = true)), autostart)

            assertEquals(BootDecision.Started(AutostartResult.Started), coordinator.onBroadcast(boot))
            assertEquals(1, autostart.calls)
        }

    @Test
    fun aRefusedServiceStartIsReportedNotThrown() =
        runBlocking {
            val refused = AutostartResult.TorrentServiceRefused("not allowed")
            val autostart = RecordingAutostart(refused)
            val coordinator =
                BootAutostartCoordinator(InMemorySettingsRepository(AppSettings(autostartOnBoot = true)), autostart)

            assertEquals(BootDecision.Started(refused), coordinator.onBroadcast(boot))
            assertEquals(1, autostart.calls)
        }

    @Test
    fun aForeignActionDoesNothingEvenWithTheSettingOn() =
        runBlocking {
            val autostart = RecordingAutostart()
            val coordinator =
                BootAutostartCoordinator(InMemorySettingsRepository(AppSettings(autostartOnBoot = true)), autostart)

            assertEquals(
                BootDecision.IgnoredAction,
                coordinator.onBroadcast("android.intent.action.LOCKED_BOOT_COMPLETED"),
            )
            assertEquals(BootDecision.IgnoredAction, coordinator.onBroadcast(null))
            assertEquals(0, autostart.calls)
        }
}
