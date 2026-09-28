package com.teachermovies.bridge.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The SSE subset the TV's job stream uses (#275), frame by frame. */
class SseParserTest {
    private fun parse(vararg lines: String): List<SseEvent> {
        val parser = SseParser()
        return lines.mapNotNull { parser.feed(it) }
    }

    @Test
    fun `a job frame is its event name and its data`() {
        assertEquals(listOf(SseEvent("job", """{"id":"a"}""")), parse("event: job", """data: {"id":"a"}""", ""))
    }

    @Test
    fun `comments such as the ping are ignored and produce no frame`() {
        assertEquals(emptyList<SseEvent>(), parse(": ping", ""))
    }

    @Test
    fun `several data lines are joined with a newline and the space after the colon is optional`() {
        assertEquals(listOf(SseEvent("message", "uno\ndos")), parse("data:uno", "data: dos", ""))
    }

    @Test
    fun `the event name does not leak into the next frame`() {
        assertEquals(
            listOf(SseEvent("cancel", "1"), SseEvent("message", "2")),
            parse("event: cancel", "data: 1", "", "data: 2", ""),
        )
    }

    @Test
    fun `a frame is only complete at its blank line, and unknown fields are ignored`() {
        val parser = SseParser()
        assertNull(parser.feed("event: job"))
        assertNull(parser.feed("id: 7"))
        assertNull(parser.feed("data: x"))
        assertEquals(SseEvent("job", "x"), parser.feed(""))
    }
}
