package com.teachermovies.player.mkv

import org.junit.Assert.assertEquals
import org.junit.Test

/** [WebVttText]: WebVTT cue markup down to what SubRip understands. */
class WebVttTextTest {
    @Test
    fun theSrtTagsAreKeptWithoutTheirClasses() {
        assertEquals("<i>a</i> <b>b</b> <u>c</u>", WebVttText.toSrt("<i.x>a</i> <b>b</b> <u.y.z>c</u>"))
    }

    @Test
    fun everyOtherTagIsDroppedAndItsTextKept() {
        assertEquals(
            "Bob: hi there kanji",
            WebVttText.toSrt("<v Bob>Bob: </v><c.red>hi</c> <lang en>there</lang> <ruby>kanji<rt></rt></ruby>"),
        )
        assertEquals("one two", WebVttText.toSrt("one <00:00:01.500>two"))
    }

    @Test
    fun characterReferencesAreDecodedAndUnknownOnesKept() {
        assertEquals("a & b <c> d e &unknown;", WebVttText.toSrt("a &amp; b &lt;c&gt; d&nbsp;e &unknown;"))
    }

    @Test
    fun plainTextAndNewlinesAreUnchanged() {
        assertEquals("Line one\nline two", WebVttText.toSrt("Line one\nline two"))
    }
}
