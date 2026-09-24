package com.teachermovies.discovery

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NsdServiceAnnouncerTest {
    private val registrar = FakeNsdRegistrar()
    private val announcer = NsdServiceAnnouncer(registrar)

    @Test
    fun announceMovesToAnnouncingUntilThePlatformAnswers() = runTest {
        announcer.announce(INFO)

        assertEquals(AnnouncementState.Announcing(INFO), announcer.state.value)
        assertEquals(listOf("register ${INFO.instanceName}"), registrar.calls)
    }

    @Test
    fun successfulRegistrationReachesAnnouncedWithTheRegisteredName() = runTest {
        announcer.announce(INFO)
        registrar.lastCallback.onRegistered(INFO.instanceName)

        assertEquals(AnnouncementState.Announced(INFO, INFO.instanceName), announcer.state.value)
    }

    @Test
    fun platformRenameSurfacesInRegisteredName() = runTest {
        announcer.announce(INFO)
        registrar.lastCallback.onRegistered("Movie Assistant (2)")

        assertEquals(
            AnnouncementState.Announced(INFO, "Movie Assistant (2)"),
            announcer.state.value,
        )
    }

    @Test
    fun registrationFailureReachesFailed() = runTest {
        announcer.announce(INFO)
        registrar.lastCallback.onFailed("NSD error 3")

        assertEquals(AnnouncementState.Failed(INFO, "NSD error 3"), announcer.state.value)
    }

    @Test
    fun announceWhileAnnouncedUnregistersBeforeRegisteringAgain() = runTest {
        announcer.announce(INFO)
        registrar.lastCallback.onRegistered(INFO.instanceName)
        val other = INFO.copy(port = 9000)

        announcer.announce(other)

        assertEquals(
            listOf("register ${INFO.instanceName}", "unregister", "register ${other.instanceName}"),
            registrar.calls,
        )
        assertEquals(AnnouncementState.Announcing(other), announcer.state.value)
        registrar.lastCallback.onRegistered(other.instanceName)
        assertEquals(AnnouncementState.Announced(other, other.instanceName), announcer.state.value)
    }

    @Test
    fun callbacksFromAReplacedRegistrationAreIgnored() = runTest {
        announcer.announce(INFO)
        val stale = registrar.lastCallback
        announcer.announce(INFO.copy(port = 9000))

        stale.onRegistered("stale")
        stale.onFailed("stale")

        assertEquals(AnnouncementState.Announcing(INFO.copy(port = 9000)), announcer.state.value)
    }

    @Test
    fun stopWhileIdleIsANoOp() = runTest {
        announcer.stop()

        assertEquals(AnnouncementState.Idle, announcer.state.value)
        assertTrue(registrar.calls.isEmpty())
    }

    @Test
    fun stopWhileAnnouncedUnregistersAndReturnsToIdle() = runTest {
        announcer.announce(INFO)
        registrar.lastCallback.onRegistered(INFO.instanceName)

        announcer.stop()

        assertEquals(listOf("register ${INFO.instanceName}", "unregister"), registrar.calls)
        assertEquals(AnnouncementState.Idle, announcer.state.value)
    }

    @Test
    fun invalidPortReachesFailedWithoutReachingTheRegistrar() = runTest {
        for (port in listOf(0, -1, 65536)) {
            val info = INFO.copy(port = port)

            announcer.announce(info)

            val state = announcer.state.value
            assertTrue(state is AnnouncementState.Failed)
            assertEquals(info, (state as AnnouncementState.Failed).info)
        }
        assertTrue(registrar.calls.isEmpty())
    }

    @Test
    fun throwingRegistrarReachesFailedInsteadOfPropagating() = runTest {
        registrar.registerThrows = IllegalStateException("NSD unavailable")

        announcer.announce(INFO)

        assertEquals(
            AnnouncementState.Failed(INFO, "register failed: NSD unavailable"),
            announcer.state.value,
        )
    }

    @Test
    fun throwingUnregisterReachesFailedInsteadOfPropagating() = runTest {
        announcer.announce(INFO)
        registrar.lastCallback.onRegistered(INFO.instanceName)
        registrar.unregisterThrows = IllegalArgumentException("listener not registered")

        announcer.stop()

        assertEquals(
            AnnouncementState.Failed(INFO, "unregister failed: listener not registered"),
            announcer.state.value,
        )
    }

    private class FakeNsdRegistrar : NsdRegistrar {
        val calls = mutableListOf<String>()
        var registerThrows: Exception? = null
        var unregisterThrows: Exception? = null
        private var callback: RegistrationCallback? = null

        val lastCallback: RegistrationCallback
            get() = checkNotNull(callback) { "register() was never called" }

        override fun register(info: TvServiceInfo, callback: RegistrationCallback) {
            calls += "register ${info.instanceName}"
            this.callback = callback
            registerThrows?.let { throw it }
        }

        override fun unregister() {
            calls += "unregister"
            unregisterThrows?.let { throw it }
        }
    }

    private companion object {
        val INFO = TvServiceInfo(instanceName = "Movie Assistant", port = 8787)
    }
}
