package com.teachermovies.torrent.service

import org.junit.Assert.assertEquals
import org.junit.Test

// Literals, not the `ServiceInfo` constants: this is a JVM test, so it must not load an Android
// class (#129).
private const val DATA_SYNC = 1 // ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
private const val SPECIAL_USE = 1_073_741_824 // ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

class ForegroundServiceTypesTest {
    @Test
    fun `below API 29 has no foreground-service type`() {
        assertEquals(0, ForegroundServiceTypes.forSdk(26))
        assertEquals(0, ForegroundServiceTypes.forSdk(28))
    }

    @Test
    fun `API 29 to 33 is dataSync`() {
        assertEquals(DATA_SYNC, ForegroundServiceTypes.forSdk(29))
        assertEquals(DATA_SYNC, ForegroundServiceTypes.forSdk(33))
    }

    @Test
    fun `API 34 and above is specialUse`() {
        assertEquals(SPECIAL_USE, ForegroundServiceTypes.forSdk(34))
        assertEquals(SPECIAL_USE, ForegroundServiceTypes.forSdk(35))
    }
}
