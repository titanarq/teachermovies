package com.teachermovies.http.auth

import com.teachermovies.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Outcome of [PairingManager.pair]. */
sealed interface PairResult {
    /** The PIN matched; [token] is the bearer token the phone sends from now on. */
    data class Paired(
        val token: String,
    ) : PairResult {
        // Never print the token (AGENTS.md: never log tokens).
        override fun toString(): String = "Paired(token=<redacted>)"
    }

    /** The PIN did not match the one currently shown on the TV. */
    data object WrongPin : PairResult

    /** Too many wrong PINs in the last minute; every attempt is refused until the window passes. */
    data object TooManyAttempts : PairResult
}

/**
 * PIN pairing and bearer-token validation (ADR-0002, scopes by ADR-0005 §4).
 *
 * The TV shows [currentPin]; a phone or a laptop bridge exchanges it for a token with [pair]. Only
 * the SHA-256 hex of a token is persisted, so tokens survive a restart without ever being stored in
 * clear -- in [SettingsRepository.addAuthTokenHash] for a phone-scoped token and
 * [SettingsRepository.addBridgeTokenHash] for a bridge-scoped one, the two sets kept apart so
 * [scopeOf] can tell which kind of client a token was issued to. PINs and tokens are never logged
 * or put in messages.
 *
 * - The PIN is 6 digits, zero-padded, and is regenerated every [PIN_LIFETIME_MS] and after every
 *   successful pairing (a PIN pairs one client only, whichever scope it asked for).
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

    /**
     * Exchanges [pin] for a new token of [scope], stores the token's hash in that scope's set and
     * rotates the PIN. A bridge pairing also records [deviceName] (trimmed, control characters
     * dropped, at most [MAX_DEVICE_NAME_LENGTH] characters; none clears the stored one) so
     * Configuración can name the paired laptop (#289); a phone's is not stored.
     */
    suspend fun pair(
        pin: String,
        scope: TokenScope = TokenScope.PHONE,
        deviceName: String? = null,
    ): PairResult {
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
        val hash = sha256Hex(token)
        when (scope) {
            TokenScope.PHONE -> settings.addAuthTokenHash(hash)
            TokenScope.BRIDGE -> {
                settings.addBridgeTokenHash(hash)
                settings.setBridgeDeviceName(deviceName?.let(::cleanDeviceName))
            }
        }
        return PairResult.Paired(token)
    }

    /**
     * The scope a pairing issued [token] with, or null when no pairing issued it (this run or an
     * earlier one with the same settings). [requireBearer] compares it with the scopes a route
     * accepts, so a token of the wrong scope is refused exactly like an unknown one.
     */
    suspend fun scopeOf(token: String): TokenScope? {
        if (token.isEmpty()) return null
        val candidate = sha256Hex(token).toByteArray(Charsets.US_ASCII)
        val stored = settings.settings.first()
        // Compare against every stored hash in constant time, without stopping at the first match,
        // and check both scopes so the answer does not depend on where a match was found.
        val phone = matchesAny(candidate, stored.authTokenHashes)
        val bridge = matchesAny(candidate, stored.bridgeTokenHashes)
        return when {
            phone -> TokenScope.PHONE
            bridge -> TokenScope.BRIDGE
            else -> null
        }
    }

    private fun matchesAny(
        candidate: ByteArray,
        hashes: Set<String>,
    ): Boolean {
        var match = false
        for (stored in hashes) {
            match = MessageDigest.isEqual(candidate, stored.toByteArray(Charsets.US_ASCII)) or match
        }
        return match
    }

    private fun cleanDeviceName(name: String): String? =
        name
            .filterNot(Char::isISOControl)
            .trim()
            .take(MAX_DEVICE_NAME_LENGTH)
            .trim()
            .ifEmpty { null }

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

        /** Longest bridge name [pair] stores; a longer one is cut, not refused. */
        const val MAX_DEVICE_NAME_LENGTH = 64
        private const val PIN_LENGTH = 6
        private const val PIN_BOUND = 1_000_000
        private const val TOKEN_BYTES = 32

        /**
         * Lower-case hex SHA-256 of [token], the form stored in `AppSettings.authTokenHashes` or
         * `AppSettings.bridgeTokenHashes`.
         */
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
