package com.teachermovies.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreModelPlaceholderTest {
    @Test
    fun placeholderIsCompiledIntoTheModuleNamespace() {
        assertEquals("com.teachermovies.core", CoreModelPlaceholder::class.java.packageName)
    }
}
