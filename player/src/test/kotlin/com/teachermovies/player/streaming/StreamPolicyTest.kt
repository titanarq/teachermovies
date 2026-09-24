package com.teachermovies.player.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPolicyTest {
    private val mib = 1024L * 1024L

    private fun assertRejected(
        field: String,
        build: () -> StreamPolicy,
    ) {
        val error = assertThrows(IllegalArgumentException::class.java) { build() }
        assertTrue("message should name $field: ${error.message}", error.message!!.contains(field))
    }

    @Test
    fun defaultHasTheDocumentedValues() {
        val p = StreamPolicy.DEFAULT
        assertEquals(2 * mib, p.headBytes)
        assertEquals(1 * mib, p.tailBytes)
        assertEquals(16 * mib, p.startBufferBytes)
        assertEquals(48 * mib, p.readAheadBytes)
        assertEquals(2 * mib, p.underrunBytes)
        assertEquals(12 * mib, p.resumeBytes)
    }

    @Test
    fun rejectsNonPositiveHead() {
        assertRejected("headBytes") { StreamPolicy.DEFAULT.copy(headBytes = 0) }
    }

    @Test
    fun rejectsNonPositiveTail() {
        assertRejected("tailBytes") { StreamPolicy.DEFAULT.copy(tailBytes = -1) }
    }

    @Test
    fun rejectsNonPositiveStartBuffer() {
        assertRejected("startBufferBytes") { StreamPolicy.DEFAULT.copy(startBufferBytes = 0) }
    }

    @Test
    fun rejectsNonPositiveReadAhead() {
        assertRejected("readAheadBytes") { StreamPolicy.DEFAULT.copy(readAheadBytes = 0) }
    }

    @Test
    fun rejectsNonPositiveUnderrun() {
        assertRejected("underrunBytes") { StreamPolicy.DEFAULT.copy(underrunBytes = 0) }
    }

    @Test
    fun rejectsNonPositiveResume() {
        assertRejected("resumeBytes") { StreamPolicy.DEFAULT.copy(resumeBytes = 0) }
    }

    @Test
    fun rejectsResumeBelowUnderrun() {
        assertRejected("resumeBytes") {
            StreamPolicy.DEFAULT.copy(underrunBytes = 4 * mib, resumeBytes = 3 * mib)
        }
    }

    @Test
    fun acceptsResumeEqualToUnderrun() {
        val p = StreamPolicy.DEFAULT.copy(underrunBytes = 4 * mib, resumeBytes = 4 * mib)
        assertEquals(p.underrunBytes, p.resumeBytes)
    }
}
