package com.teachermovies.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerPlaceholderTest {
    @Test
    fun placeholderIsCompiledIntoTheModuleNamespace() {
        assertEquals("com.teachermovies.player", PlayerPlaceholder::class.java.packageName)
    }
}
