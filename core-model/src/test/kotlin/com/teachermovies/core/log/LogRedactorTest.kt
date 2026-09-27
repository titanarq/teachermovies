package com.teachermovies.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {
    private val redactor = LogRedactor()

    /**
     * One row of the table ADR-0006 asks for: [input] must come out of [LogRedactor.redact] as
     * exactly [expected]. The rows that must survive untouched are part of the table too -- they
     * are where a rule would cost diagnosis more than it protects.
     */
    private data class Redaction(
        val name: String,
        val input: String,
        val expected: String,
    )

    private val table =
        listOf(
            Redaction(
                name = "an Authorization header carrying a bearer token",
                input = "Authorization: Bearer $TOKEN",
                expected = "Authorization: $REDACTED",
            ),
            Redaction(
                name = "an Authorization header without the bearer scheme",
                input = "authorization=$TOKEN",
                expected = "authorization=$REDACTED",
            ),
            Redaction(
                name = "an Authorization header inside a JSON body",
                input = """{"authorization": "Bearer $TOKEN"}""",
                expected = """{"authorization": "$REDACTED"}""",
            ),
            Redaction(
                name = "a bearer token quoted on its own",
                input = "request carried Bearer $TOKEN",
                expected = "request carried Bearer $REDACTED",
            ),
            Redaction(
                name = "a token= value",
                input = "pairing accepted, token=$TOKEN",
                expected = "pairing accepted, token=$REDACTED",
            ),
            Redaction(
                name = "an access_token in JSON, with the rest of the body left alone",
                input = """{"access_token": "$TOKEN", "scope": "bridge"}""",
                expected = """{"access_token": "$REDACTED", "scope": "bridge"}""",
            ),
            Redaction(
                name = "a password= value",
                input = "password=hunter2",
                expected = "password=$REDACTED",
            ),
            Redaction(
                name = "a password in JSON",
                input = """{"password": "hunter2"}""",
                expected = """{"password": "$REDACTED"}""",
            ),
            Redaction(
                name = "an Api-Key header",
                input = "Api-Key: abc123def456",
                expected = "Api-Key: $REDACTED",
            ),
            Redaction(
                name = "an OPENSUBTITLES_* environment value",
                input = "OPENSUBTITLES_APIKEY=theLaptopKey",
                expected = "OPENSUBTITLES_APIKEY=$REDACTED",
            ),
            Redaction(
                name = "an Anthropic key",
                input = "translation failed for sk-ant-api03-AbCdEf1234567890",
                expected = "translation failed for $REDACTED",
            ),
            Redaction(
                name = "a pin= value",
                input = "pin=$PIN shown on screen",
                expected = "pin=$REDACTED shown on screen",
            ),
            Redaction(
                name = "a PIN written as a bare six-digit group",
                input = "PIN: $PIN",
                expected = "PIN: $REDACTED",
            ),
            Redaction(
                name = "a pairingPin in JSON",
                input = """{"pairingPin": "$PIN"}""",
                expected = """{"pairingPin": "$REDACTED"}""",
            ),
            Redaction(
                name = "a JWT",
                input = "decoded $JWT",
                expected = "decoded $REDACTED",
            ),
            Redaction(
                name = "a bare 43-character base64url token",
                input = "curl -H $TOKEN",
                expected = "curl -H $REDACTED",
            ),
            Redaction(
                name = "a bare 43-character base64url token that starts with a dash",
                input = "curl -H $TOKEN_LEADING_DASH",
                expected = "curl -H $REDACTED",
            ),
            Redaction(
                name = "a bare 43-character base64url token that starts with an underscore",
                input = "curl -H $TOKEN_LEADING_UNDERSCORE",
                expected = "curl -H $REDACTED",
            ),
            Redaction(
                name = "a bare 43-character base64url token that ends with a dash",
                input = "curl -H $TOKEN_TRAILING_DASH",
                expected = "curl -H $REDACTED",
            ),
            Redaction(
                name = "a line that only mentions authorization in prose",
                input = "GET /api/logs refused: authorization missing",
                expected = "GET /api/logs refused: authorization missing",
            ),
            Redaction(
                name = "an info hash, which is 40 hex characters and no secret",
                input = "added torrent $INFO_HASH",
                expected = "added torrent $INFO_HASH",
            ),
            Redaction(
                name = "a magnet URI",
                input = "magnet:?xt=urn:btih:$INFO_HASH&dn=Inception",
                expected = "magnet:?xt=urn:btih:$INFO_HASH&dn=Inception",
            ),
            Redaction(
                name = "an ordinary progress line",
                input = "downloaded 42% of Inception.mkv in 3.2 s",
                expected = "downloaded 42% of Inception.mkv in 3.2 s",
            ),
        )

    @Test
    fun redactsEveryRowOfTheTable() {
        table.forEach { case ->
            assertEquals(case.name, case.expected, redactor.redact(case.input))
        }
    }

    @Test
    fun redactsEveryRowOfTheTableToTheSameResultTheSecondTimeRound() {
        table.forEach { case ->
            val once = redactor.redact(case.input)
            assertEquals(case.name, once, redactor.redact(once))
        }
    }

    @Test
    fun samplesTheShapesTheRulesAreWrittenAgainst() {
        assertEquals("the bearer token PairingManager issues is 43 base64url characters", 43, TOKEN.length)
        assertTrue("a JWT starts with its header", JWT.startsWith("eyJ"))
        assertEquals("an info hash is 40 hex characters", 40, INFO_HASH.length)
        assertEquals("a pairing PIN is 6 digits", 6, PIN.length)
    }

    @Test
    fun replacesARegisteredSecretWhereverItAppears() {
        redactor.registerSecret(PIN)

        assertEquals("El PIN es $REDACTED", redactor.redact("El PIN es $PIN"))
        assertEquals(
            "pairing: $REDACTED accepted, screen keeps $REDACTED",
            redactor.redact("pairing: $PIN accepted, screen keeps $PIN"),
        )
    }

    @Test
    fun ignoresARegisteredSecretTooShortToTrust() {
        redactor.registerSecret("123")

        assertEquals("123 lines written", redactor.redact("123 lines written"))
        assertEquals(emptyList<String>(), redactor.registeredSecrets)
    }

    @Test
    fun stopsReplacingARegisteredSecretOnceItIsUnregistered() {
        // "El PIN es 123456" is a line no pattern rule reaches: only the registered value does.
        redactor.registerSecret(PIN)
        assertEquals("El PIN es $REDACTED", redactor.redact("El PIN es $PIN"))

        redactor.unregisterSecret(PIN)

        assertEquals("El PIN es $PIN", redactor.redact("El PIN es $PIN"))
    }

    @Test
    fun keepsTheRestOfALineThatHoldsARegisteredSecret() {
        redactor.registerSecret("a-very-specific-value")

        val redacted = redactor.redact("volume a-very-specific-value has 12 GiB free")

        assertEquals("volume $REDACTED has 12 GiB free", redacted)
        assertFalse(redacted.contains("a-very-specific-value"))
    }

    private companion object {
        const val REDACTED = LogRedactor.REDACTED
        const val TOKEN = "abcdefghijabcdefghijabcdefghijabcdefghijxyz"

        // TOKEN's length with a `-` or `_` at one end -- the shapes `\b` cannot bound. Derived
        // from TOKEN so they cannot drift out of the 43 characters the rule counts.
        val TOKEN_LEADING_DASH = "-${TOKEN.drop(1)}"
        val TOKEN_LEADING_UNDERSCORE = "_${TOKEN.drop(1)}"
        val TOKEN_TRAILING_DASH = "${TOKEN.dropLast(1)}-"

        const val JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0aXRhbiJ9.abcDEF123"
        const val INFO_HASH = "3a7bd3e2360a3d29eea436fcfb7e44c735d117c4"
        const val PIN = "123456"
    }
}
