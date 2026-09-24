package com.teachermovies.torrent

import org.junit.Assert.assertEquals
import org.junit.Test

class TorrentPlaceholderTest {
    @Test
    fun placeholderIsCompiledIntoTheModuleNamespace() {
        assertEquals("com.teachermovies.torrent", TorrentPlaceholder::class.java.packageName)
    }
}
