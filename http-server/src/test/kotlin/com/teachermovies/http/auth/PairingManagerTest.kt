package com.teachermovies.http.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingManagerTest {
    private val settings = InMemorySettingsRepository()
    private val clock = FakeClock()
    private val manager = PairingManager(settings, CountingSecureRandom(), clock)

    private fun wrongPin(): String = ((manager.currentPin().toInt() + 1) % 1_000_000).toString().padStart(6, '0')

    private suspend fun pairedToken(): String = (manager.pair(manager.currentPin()) as PairResult.Paired).token

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

            assertTrue(manager.isValid(token))
            assertFalse(manager.isValid(token + "x"))
            assertFalse(manager.isValid(""))
            assertFalse(manager.isValid(PairingManager.sha256Hex(token)))
        }

    @Test
    fun `tokens stay valid after restart with the same settings`() =
        runTest {
            val first = pairedToken()
            val second = pairedToken()
            assertNotEquals(first, second)

            val restarted = PairingManager(settings, CountingSecureRandom(), FakeClock())

            assertTrue(restarted.isValid(first))
            assertTrue(restarted.isValid(second))
        }

    @Test
    fun `clearing token hashes revokes tokens`() =
        runTest {
            val token = pairedToken()
            settings.clearAuthTokenHashes()

            assertFalse(manager.isValid(token))
        }
}
