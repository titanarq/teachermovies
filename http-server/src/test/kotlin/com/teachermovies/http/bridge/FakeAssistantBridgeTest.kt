package com.teachermovies.http.bridge

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class FakeAssistantBridgeTest {
    @Test
    fun `answers with respond while connected and NoBridge otherwise, recording every job`() =
        runTest {
            val fake = FakeAssistantBridge(respond = { BridgeOutcome.Done("hola") })
            assertEquals(BridgeOutcome.Done("hola"), fake.submit(BridgeJob.Translate("hi"), 1.seconds))
            fake.setConnected(false)
            assertEquals(false, fake.connected.value)
            assertEquals(BridgeOutcome.NoBridge, fake.submit(BridgeJob.Translate("bye"), 1.seconds))
            assertEquals(listOf(BridgeJob.Translate("hi"), BridgeJob.Translate("bye")), fake.submitted)
        }
}
