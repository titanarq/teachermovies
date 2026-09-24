package com.teachermovies.http

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpServerPlaceholderTest {
    @Test
    fun placeholderIsCompiledIntoTheModuleNamespace() {
        assertEquals("com.teachermovies.http", HttpServerPlaceholder::class.java.packageName)
    }
}
