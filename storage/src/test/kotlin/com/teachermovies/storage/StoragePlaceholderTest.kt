package com.teachermovies.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class StoragePlaceholderTest {
    @Test
    fun placeholderIsCompiledIntoTheModuleNamespace() {
        assertEquals("com.teachermovies.storage", StoragePlaceholder::class.java.packageName)
    }
}
