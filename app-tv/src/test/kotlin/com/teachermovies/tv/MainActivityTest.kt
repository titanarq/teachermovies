package com.teachermovies.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityTest {
    @Test
    fun mainActivityIsCompiledIntoTheModuleNamespace() {
        assertEquals("com.teachermovies.tv", MainActivity::class.java.packageName)
    }

    @Test
    fun mainActivityIsAComponentActivitySoItCanHostTheComposeShell() {
        assertEquals(
            "androidx.activity.ComponentActivity",
            MainActivity::class.java.superclass?.name,
        )
    }
}
