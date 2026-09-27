package com.teachermovies.core.log

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Takes secrets out of a log line before [RingBufferLogSink] stores it (ADR-0006).
 *
 * This is a second line of defence, not a licence to log secrets: callers still never log one
 * (AGENTS.md), and what this replaces is gone for good, because the buffer it protects is served
 * over the HTTP API to the phone and mirrored to a file on the laptop.
 *
 * Two kinds of rule run over every message, in this order:
 * - the shapes a secret takes in this project: a JWT, an `Authorization` header, a bare `Bearer`
 *   token, `token=`/`password=`/`Api-Key`/`OPENSUBTITLES_*` values, an `sk-ant-` Anthropic key, a
 *   PIN, and the 43-character base64url token `PairingManager` issues (32 random bytes);
 * - the values only the running process knows, added with [registerSecret] -- the current pairing
 *   PIN, for as long as it is on screen.
 *
 * A redacted value becomes [REDACTED]; whatever named it (`token=`, `Api-Key:`, `"pin":`) is kept,
 * so the line still says what was there. Redaction is idempotent: a [REDACTED] matches no rule.
 *
 * Thread-safe -- the rules are immutable and the registered secrets live in a copy-on-write set --
 * and it drops nothing but the secret itself: an unknown message shape passes through untouched.
 */
class LogRedactor {
    private val secrets = CopyOnWriteArraySet<String>()

    /**
     * Adds [secret] to the values replaced wherever they appear, for a secret no pattern can
     * recognise (the current PIN). Values shorter than [MIN_REGISTERED_SECRET_LENGTH] characters
     * are ignored: a four-character needle is a false positive waiting to happen, and every secret
     * this project issues is longer.
     */
    fun registerSecret(secret: String) {
        if (secret.length >= MIN_REGISTERED_SECRET_LENGTH) secrets.add(secret)
    }

    /** Removes a secret added with [registerSecret], for when it stops being the current one. */
    fun unregisterSecret(secret: String) {
        secrets.remove(secret)
    }

    /** The values [redact] replaces, longest first -- the order they are applied in. */
    val registeredSecrets: List<String> get() = secrets.sortedByDescending { it.length }

    /** Returns [message] with every secret it recognises replaced by [REDACTED]. */
    fun redact(message: String): String {
        var redacted = message
        PATTERNS.forEach { (pattern, replacement) -> redacted = pattern.replace(redacted, replacement) }
        registeredSecrets.forEach { secret -> redacted = redacted.replace(secret, REDACTED) }
        return redacted
    }

    companion object {
        /** What a redacted value becomes. */
        const val REDACTED = "[REDACTED]"

        /** Shortest value [registerSecret] accepts. */
        const val MIN_REGISTERED_SECRET_LENGTH = 4

        /**
         * A secret's value: everything up to whitespace or up to a character that ends it in the
         * text it sits in (a quote, a comma, a closing brace), so redacting a JSON body leaves
         * valid JSON behind. The lookahead keeps a value that is already [REDACTED] from matching
         * again -- that is what makes [redact] idempotent, and what stops two rules that name the
         * same secret (`Api-Key` and `OPENSUBTITLES_APIKEY`) from leaving two markers behind.
         */
        private val VALUE = "(?!" + Regex.escape(REDACTED) + ")" + """[^\s"',;}\]]+"""

        private val PATTERNS: List<Pair<Regex, String>> =
            listOf(
                // A whole JWT, before the bare-token rule below can pick off one of its segments.
                Regex("""\beyJ[A-Za-z0-9_-]*\.[A-Za-z0-9_-]*\.[A-Za-z0-9_-]*""") to REDACTED,
                // `Authorization: Bearer abc`, `authorization=abc`, `{"authorization": "Bearer abc"}`.
                // The separator is not optional, so a line that merely says "authorization failed"
                // survives: the HTTP server logs refusals and they have to stay readable.
                Regex("""(?i)\b(authorization["']?\s*[:=]\s*["']?)(?:bearer\s+)?$VALUE""") to KEEP_KEY,
                // `Bearer abc` on its own, where the value looks like a token and not like prose.
                Regex("""(?i)\b(bearer\s+)[A-Za-z0-9._~+/=-]{16,}""") to KEEP_KEY,
                // `token=abc`, `"access_token": "abc"`.
                Regex("""(?i)\b(\w*token["']?\s*[:=]\s*["']?)[A-Za-z0-9._~+/=-]+""") to KEEP_KEY,
                Regex("""(?i)\b(\w*password["']?\s*[:=]\s*["']?)$VALUE""") to KEEP_KEY,
                Regex("""(?i)\b(\w*api[-_]?key["']?\s*[:=]\s*["']?)$VALUE""") to KEEP_KEY,
                // The bridge's OpenSubtitles credentials, `.secrets/opensubtitles.env` (ADR-0005).
                Regex("""(?i)\b(OPENSUBTITLES_[A-Z0-9_]*["']?\s*[:=]\s*["']?)$VALUE""") to KEEP_KEY,
                // An Anthropic API key, which the TV no longer stores but a log line could carry.
                Regex("""\bsk-ant-[A-Za-z0-9_-]*""") to REDACTED,
                // `pin=123456`, `PIN 123456`, `"pairingPin": "123456"` -- the 6-digit pairing PIN.
                Regex("""(?i)\b(\w*pin["']?\s*[:=]?\s*["']?)\d{4,8}\b""") to KEEP_KEY,
                // The bearer token `PairingManager` issues: 32 random bytes, base64url, no padding.
                // Lookarounds, not `\b`: `-` and `_` belong to that alphabet but are non-word
                // characters, so `\b` found no boundary at them and let a token with one at an end
                // through. The class is the alphabet, so a 43-run inside a longer one stays whole.
                Regex("""(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])""") to REDACTED,
            )

        private const val KEEP_KEY = "\$1$REDACTED"
    }
}
