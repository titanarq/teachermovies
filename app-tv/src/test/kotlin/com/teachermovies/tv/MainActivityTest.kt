package com.teachermovies.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityTest {
    @Test
    fun activityStubIsCompiledIntoTheModuleNamespace() {
        assertEquals("com.teachermovies.tv", MainActivity::class.java.packageName)
    }
}
