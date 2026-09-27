package com.teachermovies.http.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingManagerTest {
    private val settings = InMemorySettingsRepository()
    private val clock = FakeClock()
    private val manager = PairingManager(settings, CountingSecureRandom(), clock)

    private fun wrongPin(): String = ((manager.currentPin().toInt() + 1) % 1_000_000).toString().padStart(6, '0')

    private suspend fun pairedToken(scope: TokenScope = TokenScope.PHONE): String =
        (manager.pair(manager.currentPin(), scope) as PairResult.Paired).token

    @Test
    fun `pin is six zero-padded digits and stable within its lifetime`() {
        val pin = manager.currentPin()
        assertTrue(Regex("\\d{6}").matches(pin))
        clock.now += PairingManager.PIN_LIFETIME_MS - 1
        assertEquals(pin, manager.currentPin())
    }

    @Test
    fun `pin rotates after ten minutes`() {
        val pin = manager.currentPin()
        clock.now += PairingManager.PIN_LIFETIME_MS
        assertNotEquals(pin, manager.currentPin())
    }

    @Test
    fun `an expired pin no longer pairs`() =
        runTest {
            val pin = manager.currentPin()
            clock.now += PairingManager.PIN_LIFETIME_MS

            assertEquals(PairResult.WrongPin, manager.pair(pin))
        }

    @Test
    fun `right pin pairs, stores only the token hash and rotates the pin`() =
        runTest {
            val pin = manager.currentPin()

            val result = manager.pair(pin)

            assertTrue(result is PairResult.Paired)
            val token = (result as PairResult.Paired).token
            // 32 random bytes, base64url without padding.
            assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(token))
            assertEquals(setOf(PairingManager.sha256Hex(token)), settings.current.authTokenHashes)
            assertFalse(result.toString().contains(token))
            assertNotEquals(pin, manager.currentPin())
            assertEquals(PairResult.WrongPin, manager.pair(pin))
        }

    @Test
    fun `sha256Hex is lower-case hex`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            PairingManager.sha256Hex("abc"),
        )
    }

    @Test
    fun `wrong pin is rejected and stores nothing`() =
        runTest {
            assertEquals(PairResult.WrongPin, manager.pair(wrongPin()))
            assertEquals(PairResult.WrongPin, manager.pair(""))
            assertTrue(settings.current.authTokenHashes.isEmpty())
        }

    @Test
    fun `more than five wrong pins within a minute is rate limited, even with the right pin`() =
        runTest {
            repeat(PairingManager.MAX_WRONG_ATTEMPTS) {
                assertEquals(PairResult.WrongPin, manager.pair(wrongPin()))
                clock.now += 1_000
            }

            assertEquals(PairResult.TooManyAttempts, manager.pair(wrongPin()))
            assertEquals(PairResult.TooManyAttempts, manager.pair(manager.currentPin()))
            assertTrue(settings.current.authTokenHashes.isEmpty())
        }

    @Test
    fun `rate limit lifts once the wrong attempts leave the window`() =
        runTest {
            val start = clock.now
            repeat(PairingManager.MAX_WRONG_ATTEMPTS) { manager.pair(wrongPin()) }
            assertEquals(PairResult.TooManyAttempts, manager.pair(manager.currentPin()))

            clock.now = start + PairingManager.ATTEMPT_WINDOW_MS

            assertTrue(manager.pair(manager.currentPin()) is PairResult.Paired)
        }

    @Test
    fun `wrong attempts spread over more than a minute are not rate limited`() =
        runTest {
            repeat(10) {
                assertEquals(PairResult.WrongPin, manager.pair(wrongPin()))
                clock.now += 15_000
            }
        }

    @Test
    fun `issued token is valid, others are not`() =
        runTest {
            val token = pairedToken()

            assertEquals(TokenScope.PHONE, manager.scopeOf(token))
            assertNull(manager.scopeOf(token + "x"))
            assertNull(manager.scopeOf(""))
            assertNull(manager.scopeOf(PairingManager.sha256Hex(token)))
        }

    @Test
    fun `tokens stay valid after restart with the same settings`() =
        runTest {
            val first = pairedToken()
            val second = pairedToken()
            assertNotEquals(first, second)

            val restarted = PairingManager(settings, CountingSecureRandom(), FakeClock())

            assertEquals(TokenScope.PHONE, restarted.scopeOf(first))
            assertEquals(TokenScope.PHONE, restarted.scopeOf(second))
        }

    @Test
    fun `clearing token hashes revokes tokens`() =
        runTest {
            val token = pairedToken()
            settings.clearAuthTokenHashes()

            assertNull(manager.scopeOf(token))
        }

    @Test
    fun `pairing without a scope issues a phone token`() =
        runTest {
            val result = manager.pair(manager.currentPin())

            val token = (result as PairResult.Paired).token
            assertEquals(setOf(PairingManager.sha256Hex(token)), settings.current.authTokenHashes)
            assertTrue(settings.current.bridgeTokenHashes.isEmpty())
            assertEquals(TokenScope.PHONE, manager.scopeOf(token))
        }

    @Test
    fun `bridge pairing stores its hash apart from the phone hashes`() =
        runTest {
            val bridgeToken = pairedToken(TokenScope.BRIDGE)
            val phoneToken = pairedToken(TokenScope.PHONE)
            assertNotEquals(bridgeToken, phoneToken)

            assertEquals(setOf(PairingManager.sha256Hex(bridgeToken)), settings.current.bridgeTokenHashes)
            assertEquals(setOf(PairingManager.sha256Hex(phoneToken)), settings.current.authTokenHashes)
            assertEquals(TokenScope.BRIDGE, manager.scopeOf(bridgeToken))
            assertEquals(TokenScope.PHONE, manager.scopeOf(phoneToken))
        }

    @Test
    fun `a bridge token survives a restart in its own scope`() =
        runTest {
            val bridgeToken = pairedToken(TokenScope.BRIDGE)

            val restarted = PairingManager(settings, CountingSecureRandom(), FakeClock())

            assertEquals(TokenScope.BRIDGE, restarted.scopeOf(bridgeToken))
        }

    @Test
    fun `clearing the phone hashes leaves a bridge token alone`() =
        runTest {
            val bridgeToken = pairedToken(TokenScope.BRIDGE)
            pairedToken(TokenScope.PHONE)
            settings.clearAuthTokenHashes()

            assertEquals(TokenScope.BRIDGE, manager.scopeOf(bridgeToken))
        }

    @Test
    fun `bridge pairing records the bridge name, cleaned and bounded`() =
        runTest {
            manager.pair(manager.currentPin(), TokenScope.BRIDGE, "  portatil\u0007-manuel  ")
            assertEquals("portatil-manuel", settings.current.bridgeDeviceName)

            manager.pair(manager.currentPin(), TokenScope.BRIDGE, "x".repeat(200))
            assertEquals("x".repeat(PairingManager.MAX_DEVICE_NAME_LENGTH), settings.current.bridgeDeviceName)

            manager.pair(manager.currentPin(), TokenScope.BRIDGE, null)
            assertNull(settings.current.bridgeDeviceName)
        }

    @Test
    fun `phone pairing stores no device name`() =
        runTest {
            manager.pair(manager.currentPin(), TokenScope.PHONE, "telefono")

            assertNull(settings.current.bridgeDeviceName)
        }

    @Test
    fun `clearing the bridge hashes revokes a bridge token and leaves a phone token alone`() =
        runTest {
            val bridgeToken = pairedToken(TokenScope.BRIDGE)
            val phoneToken = pairedToken(TokenScope.PHONE)

            settings.clearBridgeTokenHashes()

            assertNull(manager.scopeOf(bridgeToken))
            assertEquals(TokenScope.PHONE, manager.scopeOf(phoneToken))
        }

    @Test
    fun `a wrong pin pairs no bridge token`() =
        runTest {
            assertEquals(PairResult.WrongPin, manager.pair(wrongPin(), TokenScope.BRIDGE))

            assertTrue(settings.current.bridgeTokenHashes.isEmpty())
            assertTrue(manager.pair(manager.currentPin(), TokenScope.BRIDGE) is PairResult.Paired)
        }
}
