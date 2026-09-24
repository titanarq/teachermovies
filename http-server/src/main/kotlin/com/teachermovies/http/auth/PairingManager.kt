package com.teachermovies.http.auth

import com.teachermovies.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Outcome of [PairingManager.pair]. */
sealed interface PairResult {
    /** The PIN matched; [token] is the bearer token the phone sends from now on. */
    data class Paired(val token: String) : PairResult {
        // Never print the token (AGENTS.md: never log tokens).
        override fun toString(): String = "Paired(token=<redacted>)"
    }

    /** The PIN did not match the one currently shown on the TV. */
    data object WrongPin : PairResult

    /** Too many wrong PINs in the last minute; every attempt is refused until the window passes. */
    data object TooManyAttempts : PairResult
}

/**
 * PIN pairing and bearer-token validation (ADR-0002).
 *
 * The TV shows [currentPin]; a phone exchanges it for a token with [pair]. Only the SHA-256 hex of a
 * token is persisted (via [SettingsRepository.addAuthTokenHash]), so tokens survive a restart
 * without ever being stored in clear. PINs and tokens are never logged or put in messages.
 *
 * - The PIN is 6 digits, zero-padded, and is regenerated every [PIN_LIFETIME_MS] and after every
 *   successful pairing (a PIN pairs one phone only).
 * - After [MAX_WRONG_ATTEMPTS] wrong PINs within [ATTEMPT_WINDOW_MS], every further attempt (right or
 *   wrong) is [PairResult.TooManyAttempts] until the oldest of them leaves the window.
 *
 * @param clock current time in epoch milliseconds.
 */
class PairingManager(
    private val settings: SettingsRepository,
    private val random: SecureRandom,
    private val clock: () -> Long,
) {
    private val lock = Any()
    private var pin: String? = null
    private var pinIssuedAt = 0L
    private val wrongAttemptTimes = ArrayDeque<Long>()

    /** The PIN the TV should show now; a new one once the current one is 10 minutes old. */
    fun currentPin(): String = synchronized(lock) { freshPin(clock()) }

    /** Exchanges [pin] for a new token, stores the token's hash and rotates the PIN. */
    suspend fun pair(pin: String): PairResult {
        val token =
            synchronized(lock) {
                val now = clock()
                while (wrongAttemptTimes.isNotEmpty() && now - wrongAttemptTimes.first() >= ATTEMPT_WINDOW_MS) {
                    wrongAttemptTimes.removeFirst()
                }
                if (wrongAttemptTimes.size >= MAX_WRONG_ATTEMPTS) return PairResult.TooManyAttempts
                if (!constantTimeEquals(pin, freshPin(now))) {
                    wrongAttemptTimes.addLast(now)
                    return PairResult.WrongPin
                }
                // Consume the PIN before leaving the lock so a concurrent pair() with it fails.
                this.pin = null
                wrongAttemptTimes.clear()
                newToken()
            }
        settings.addAuthTokenHash(sha256Hex(token))
        return PairResult.Paired(token)
    }

    /** Whether [token] was issued by a pairing (this run or an earlier one with the same settings). */
    suspend fun isValid(token: String): Boolean {
        if (token.isEmpty()) return false
        val candidate = sha256Hex(token).toByteArray(Charsets.US_ASCII)
        var match = false
        // Compare against every stored hash in constant time, without stopping at the first match.
        for (stored in settings.settings.first().authTokenHashes) {
            match = MessageDigest.isEqual(candidate, stored.toByteArray(Charsets.US_ASCII)) or match
        }
        return match
    }

    private fun freshPin(now: Long): String {
        val current = pin
        if (current != null && now - pinIssuedAt < PIN_LIFETIME_MS) return current
        return random.nextInt(PIN_BOUND).toString().padStart(PIN_LENGTH, '0').also {
            pin = it
            pinIssuedAt = now
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val PIN_LIFETIME_MS = 10 * 60 * 1000L
        const val ATTEMPT_WINDOW_MS = 60 * 1000L
        const val MAX_WRONG_ATTEMPTS = 5
        private const val PIN_LENGTH = 6
        private const val PIN_BOUND = 1_000_000
        private const val TOKEN_BYTES = 32

        /** Lower-case hex SHA-256 of [token], the form stored in `AppSettings.authTokenHashes`. */
        fun sha256Hex(token: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(token.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        private fun constantTimeEquals(
            a: String,
            b: String,
        ): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
