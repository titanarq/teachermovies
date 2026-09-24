package com.teachermovies.tv.discovery

import com.teachermovies.discovery.ServiceNames
import com.teachermovies.discovery.TvServiceInfo
import com.teachermovies.discovery.fake.FakeServiceAnnouncer
import com.teachermovies.discovery.fake.FakeServiceAnnouncer.Call
import com.teachermovies.http.ServerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerAnnouncementCoordinatorTest {
    private val announcer = FakeServiceAnnouncer()
    private val serverState = MutableStateFlow<ServerState>(ServerState.Stopped)

    private fun TestScope.coordinator(deviceName: String? = "Shield TV") =
        ServerAnnouncementCoordinator(
            announcer = announcer,
            serverState = serverState,
            deviceName = { deviceName },
            scope = backgroundScope,
        )

    private fun announces() = announcer.calls.filterIsInstance<Call.Announce>()

    @Test
    fun runningAnnouncesOnceWithThatPortAndTheInstanceName() =
        runTest(UnconfinedTestDispatcher()) {
            coordinator().start()
            announcer.clearCalls()

            serverState.value = ServerState.Running(8787)

            val expected = TvServiceInfo(instanceName = "Movie Assistant (Shield TV)", port = 8787)
            assertEquals(listOf(Call.Announce(expected)), announcer.calls)
        }

    @Test
    fun portChangeReannouncesWithTheNewPort() =
        runTest(UnconfinedTestDispatcher()) {
            coordinator().start()
            serverState.value = ServerState.Running(8787)
            serverState.value = ServerState.Running(8788)

            assertEquals(listOf(8787, 8788), announces().map { it.info.port })
        }

    @Test
    fun repeatedIdenticalRunningDoesNotReannounce() =
        runTest(UnconfinedTestDispatcher()) {
            coordinator().start()
            serverState.value = ServerState.Running(8787)
            serverState.value = ServerState.Running(8787)

            assertEquals(1, announces().size)
        }

    @Test
    fun stoppedStopsTheAnnouncement() =
        runTest(UnconfinedTestDispatcher()) {
            coordinator().start()
            serverState.value = ServerState.Running(8787)
            announcer.clearCalls()

            serverState.value = ServerState.Stopped

            assertEquals(listOf(Call.Stop), announcer.calls)
        }

    @Test
    fun failedStopsTheAnnouncement() =
        runTest(UnconfinedTestDispatcher()) {
            coordinator().start()
            serverState.value = ServerState.Running(8787)
            announcer.clearCalls()

            serverState.value = ServerState.Failed(8787, "address in use")

            assertEquals(listOf(Call.Stop), announcer.calls)
        }

    @Test
    fun runningAgainAfterStoppedReannounces() =
        runTest(UnconfinedTestDispatcher()) {
            coordinator().start()
            serverState.value = ServerState.Running(8787)
            serverState.value = ServerState.Stopped
            serverState.value = ServerState.Running(8787)

            assertEquals(2, announces().size)
        }

    @Test
    fun nullDeviceNameStillProducesAValidInstanceName() =
        runTest(UnconfinedTestDispatcher()) {
            coordinator(deviceName = null).start()
            serverState.value = ServerState.Running(8787)

            assertEquals(ServiceNames.BASE_NAME, announcer.lastAnnounced?.instanceName)
            assertEquals(8787, announcer.lastAnnounced?.port)
        }

    @Test
    fun stopStopsTheAnnouncementAndEndsTheCollection() =
        runTest(UnconfinedTestDispatcher()) {
            val coordinator = coordinator()
            coordinator.start()
            serverState.value = ServerState.Running(8787)
            announcer.clearCalls()

            coordinator.stop()
            assertEquals(listOf(Call.Stop), announcer.calls)

            serverState.value = ServerState.Running(9000)
            serverState.value = ServerState.Stopped
            assertEquals(listOf(Call.Stop), announcer.calls)
            assertTrue(announces().isEmpty())
        }
}
