package com.teachermovies.discovery.client

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NsdServiceDiscovererTest {
    private val browser = FakeNsdBrowser()
    private val discoverer = NsdServiceDiscoverer(browser)

    private class Collection(val emissions: MutableList<List<DiscoveredTv>>, val job: Job)

    private fun TestScope.collect(serviceType: String = "_http._tcp"): Collection {
        val emissions = mutableListOf<List<DiscoveredTv>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                discoverer.discover(serviceType).collect { emissions += it }
            }
        return Collection(emissions, job)
    }

    @Test
    fun collectingStartsTheBrowseForTheServiceType() = runTest {
        collect("_custom._tcp")

        assertEquals(listOf("start _custom._tcp"), browser.calls)
    }

    @Test
    fun foundThenResolvedServiceAppearsInTheList() = runTest {
        val c = collect()

        browser.callback.onFound(TV_A.instanceName)
        assertEquals(emptyList<List<DiscoveredTv>>(), c.emissions)

        browser.callback.onResolved(TV_A)
        assertEquals(listOf(listOf(TV_A)), c.emissions)
        assertEquals("http://192.168.1.20:8787", c.emissions.last().single().baseUrl)
    }

    @Test
    fun lostServiceDisappears() = runTest {
        val c = collect()
        browser.callback.onFound(TV_A.instanceName)
        browser.callback.onResolved(TV_A)

        browser.callback.onLost(TV_A.instanceName)

        assertEquals(listOf(listOf(TV_A), emptyList()), c.emissions)
    }

    @Test
    fun losingAnUnresolvedServiceEmitsNothing() = runTest {
        val c = collect()
        browser.callback.onFound(TV_A.instanceName)

        browser.callback.onLost(TV_A.instanceName)

        assertEquals(emptyList<List<DiscoveredTv>>(), c.emissions)
    }

    @Test
    fun secondResolutionOfTheSameInstanceReplacesTheFirst() = runTest {
        val c = collect()
        val moved = TV_A.copy(host = "192.168.1.99", port = 9000)
        browser.callback.onFound(TV_A.instanceName)
        browser.callback.onResolved(TV_A)

        browser.callback.onResolved(moved)

        assertEquals(listOf(listOf(TV_A), listOf(moved)), c.emissions)
    }

    @Test
    fun twoServicesComeOutSortedByName() = runTest {
        val c = collect()
        browser.callback.onFound(TV_B.instanceName)
        browser.callback.onResolved(TV_B)
        browser.callback.onFound(TV_A.instanceName)
        browser.callback.onResolved(TV_A)

        assertEquals(listOf(listOf(TV_B), listOf(TV_A, TV_B)), c.emissions)
    }

    @Test
    fun resolutionFailureLeavesTheListUntouchedAndTheFlowOpen() = runTest {
        val c = collect()
        browser.callback.onFound(TV_A.instanceName)
        browser.callback.onResolved(TV_A)
        browser.callback.onFound(TV_B.instanceName)

        browser.callback.onFailed("resolve failed")

        assertEquals(listOf(listOf(TV_A)), c.emissions)
        assertTrue(c.job.isActive)

        browser.callback.onResolved(TV_B)
        assertEquals(listOf(listOf(TV_A), listOf(TV_A, TV_B)), c.emissions)
    }

    @Test
    fun startThrowingKeepsTheFlowOpen() = runTest {
        browser.throwOnStart = true
        val c = collect()

        assertTrue(c.job.isActive)
        assertEquals(emptyList<List<DiscoveredTv>>(), c.emissions)
    }

    @Test
    fun cancellingTheCollectorStopsTheBrowseExactlyOnce() = runTest {
        val c = collect()
        browser.callback.onFound(TV_A.instanceName)
        browser.callback.onResolved(TV_A)

        c.job.cancel()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("start _http._tcp", "stop"), browser.calls)
    }

    private class FakeNsdBrowser : NsdBrowser {
        val calls = mutableListOf<String>()
        var throwOnStart = false
        private var _callback: BrowseCallback? = null
        val callback: BrowseCallback
            get() = checkNotNull(_callback) { "start() was never called" }

        override fun start(serviceType: String, callback: BrowseCallback) {
            calls += "start $serviceType"
            if (throwOnStart) throw IllegalStateException("NSD unavailable")
            _callback = callback
        }

        override fun stop() {
            calls += "stop"
        }
    }

    private companion object {
        val TV_A =
            DiscoveredTv(
                instanceName = "Movie Assistant (Bedroom)",
                host = "192.168.1.20",
                port = 8787,
                attributes = mapOf("path" to "/"),
            )
        val TV_B =
            DiscoveredTv(instanceName = "Movie Assistant (Living room)", host = "192.168.1.21", port = 8787)
    }
}
