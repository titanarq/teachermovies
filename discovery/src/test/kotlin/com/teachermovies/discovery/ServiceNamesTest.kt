package com.teachermovies.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceNamesTest {
    @Test
    fun nullDeviceNameGivesTheBaseName() {
        assertEquals("Movie Assistant", ServiceNames.instanceName(null))
    }

    @Test
    fun emptyDeviceNameGivesTheBaseName() {
        assertEquals("Movie Assistant", ServiceNames.instanceName(""))
    }

    @Test
    fun blankDeviceNameGivesTheBaseName() {
        assertEquals("Movie Assistant", ServiceNames.instanceName(" \t\n "))
    }

    @Test
    fun deviceNameIsAppendedInParentheses() {
        assertEquals("Movie Assistant (Living Room TV)", ServiceNames.instanceName("Living Room TV"))
    }

    @Test
    fun whitespaceRunsAreCollapsedAndTrimmed() {
        assertEquals(
            "Movie Assistant (Living Room TV)",
            ServiceNames.instanceName("  Living \t Room\n\nTV  "),
        )
    }

    @Test
    fun longAsciiDeviceNameIsTruncatedToSixtyThreeBytes() {
        val name = ServiceNames.instanceName("x".repeat(200))

        assertEquals(63, name.toByteArray(Charsets.UTF_8).size)
        assertTrue(name.startsWith("Movie Assistant (x"))
        assertTrue(name.endsWith("x)"))
    }

    @Test
    fun nameThatFitsExactlyIsNotTruncated() {
        // "Movie Assistant (" is 17 bytes and ")" is 1, leaving 45 for the device name.
        val device = "d".repeat(45)

        assertEquals("Movie Assistant ($device)", ServiceNames.instanceName(device))
    }

    @Test
    fun truncationNeverSplitsAMultiByteCodePoint() {
        // Each "é" is 2 bytes; the 45-byte budget holds 22 of them, not 22.5.
        val name = ServiceNames.instanceName("é".repeat(40))

        assertEquals("Movie Assistant (${"é".repeat(22)})", name)
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 63)
    }

    @Test
    fun truncationNeverSplitsASurrogatePair() {
        // Each emoji is 4 bytes and two UTF-16 chars; the 45-byte budget holds 11 of them.
        val emoji = "🎬"
        val name = ServiceNames.instanceName(emoji.repeat(20))

        assertEquals("Movie Assistant (${emoji.repeat(11)})", name)
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 63)
        assertEquals(name, String(name.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test
    fun whitespaceLeftAtTheCutIsTrimmed() {
        val device = "a".repeat(44) + " bcd"

        assertEquals("Movie Assistant (${"a".repeat(44)})", ServiceNames.instanceName(device))
    }
}
