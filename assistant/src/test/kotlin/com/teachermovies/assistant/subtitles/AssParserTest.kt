package com.teachermovies.assistant.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AssParserTest {
    private val parser = AssParser()

    @Test
    fun `supports ass and ssa extensions case-insensitively, not other extensions`() {
        assertTrue(parser.supports("movie.ass"))
        assertTrue(parser.supports("movie.ASS"))
        assertTrue(parser.supports("movie.ssa"))
        assertFalse(parser.supports("movie.srt"))
    }

    @Test
    fun `reads Start, End and Text columns wherever the Format line puts them`() {
        val ass =
            """
            [Script Info]
            Title: Example

            [V4+ Styles]
            Format: Name, Fontname
            Style: Default,Arial

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,Hello there.
            """.trimIndent()

        val track = parser.parse(ass)

        assertEquals(1, track.cues.size)
        assertEquals(1_000L, track.cues[0].startMs)
        assertEquals(4_000L, track.cues[0].endMs)
        assertEquals("Hello there.", track.cues[0].text)
    }

    @Test
    fun `finds columns by name even when the Format order is unusual`() {
        val ass =
            """
            [Events]
            Format: Text, Start, Layer, End, Style
            Dialogue: Reordered cue,0:00:02.00,0,0:00:05.00,Default
            """.trimIndent()

        val track = parser.parse(ass)

        assertEquals(1, track.cues.size)
        assertEquals(2_000L, track.cues[0].startMs)
        assertEquals(5_000L, track.cues[0].endMs)
        assertEquals("Reordered cue", track.cues[0].text)
    }

    @Test
    fun `keeps commas that appear inside the Text field`() {
        val ass =
            """
            [Events]
            Format: Start, End, Text
            Dialogue: 0:00:01.00,0:00:02.00,Hello, world, how are you?
            """.trimIndent()

        val track = parser.parse(ass)

        assertEquals(1, track.cues.size)
        assertEquals("Hello, world, how are you?", track.cues[0].text)
    }

    @Test
    fun `ignores Comment lines`() {
        val ass =
            """
            [Events]
            Format: Start, End, Text
            Comment: 0:00:01.00,0:00:02.00,Not a cue.
            Dialogue: 0:00:03.00,0:00:04.00,A real cue.
            """.trimIndent()

        val track = parser.parse(ass)

        assertEquals(1, track.cues.size)
        assertEquals("A real cue.", track.cues[0].text)
    }

    @Test
    fun `ignores sections other than Events`() {
        val ass =
            """
            [Script Info]
            Dialogue: 0:00:01.00,0:00:02.00,should be ignored

            [Events]
            Format: Start, End, Text
            Dialogue: 0:00:03.00,0:00:04.00,should be parsed
            """.trimIndent()

        val track = parser.parse(ass)

        assertEquals(1, track.cues.size)
        assertEquals("should be parsed", track.cues[0].text)
    }

    @Test
    fun `strips override blocks`() {
        val ass =
            """
            [Events]
            Format: Start, End, Text
            Dialogue: 0:00:01.00,0:00:02.00,{\i1}Hello{\i0} world
            """.trimIndent()

        val track = parser.parse(ass)

        assertEquals(1, track.cues.size)
        assertEquals("Hello world", track.cues[0].text)
    }

    @Test
    fun `turns backslash-N and backslash-n into a newline and backslash-h into a space`() {
        val ass =
            """
            [Events]
            Format: Start, End, Text
            Dialogue: 0:00:01.00,0:00:02.00,First\Nsecond\nthird\hfourth
            """.trimIndent()

        val track = parser.parse(ass)

        assertEquals(1, track.cues.size)
        assertEquals("First\nsecond\nthird fourth", track.cues[0].text)
    }

    @Test
    fun `drops cues that end up empty`() {
        val ass =
            """
            [Events]
            Format: Start, End, Text
            Dialogue: 0:00:01.00,0:00:02.00,{\i1}{\i0}
            Dialogue: 0:00:03.00,0:00:04.00,Real cue.
            """.trimIndent()

        val track = parser.parse(ass)

        assertEquals(1, track.cues.size)
        assertEquals("Real cue.", track.cues[0].text)
    }

    @Test
    fun `returns an empty track when there is no Events section`() {
        val ass = "[Script Info]\nTitle: Example\n"

        val track = parser.parse(ass)

        assertEquals(0, track.cues.size)
    }

    @Test
    fun `every Regex literal in assistant main code is valid on Android ICU (no bare braces)`() {
        val mainDir = listOf(File("src/main"), File("assistant/src/main")).first { it.isDirectory }
        val literals =
            mainDir
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file -> regexLiterals(file.readText()).map { file.name to it } }
                .toList()

        val offenders = literals.filter { (_, pattern) -> hasBareBrace(pattern) }
        assertTrue("ICU-incompatible Regex literals: $offenders", offenders.isEmpty())
        assertTrue("expected the AssParser override-block pattern", literals.any { it.second == OVERRIDE_BLOCK })
    }

    @Test
    fun `the brace checker flags a bare closing brace and accepts escapes, classes and quantifiers`() {
        assertTrue(hasBareBrace("""\{[^}]*}"""))
        assertTrue(hasBareBrace("""a{b"""))
        assertFalse(hasBareBrace(OVERRIDE_BLOCK))
        assertFalse(hasBareBrace("""(\d{1,2}):(\d{2})\n{2,}[{}]"""))
    }

    private companion object {
        const val OVERRIDE_BLOCK = """\{[^}]*\}"""
        val REGEX_CALL = Regex("""Regex\(\s*(""\"(.*?)""\"|"((?:[^"\\]|\\.)*)")""", RegexOption.DOT_MATCHES_ALL)

        fun regexLiterals(source: String): List<String> =
            REGEX_CALL
                .findAll(source)
                .map { m ->
                    m.groups[2]?.value ?: unescapeKotlin(m.groupValues[3])
                }.toList()

        fun unescapeKotlin(s: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    out.append(
                        when (val n = s[i + 1]) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            else -> n
                        },
                    )
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }

        /** True if [pattern] has a `{` or `}` outside a character class that is not an escaped literal or a quantifier. */
        fun hasBareBrace(pattern: String): Boolean {
            val quantifier = Regex("""\{\d+(,\d*)?\}""")
            var inClass = false
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                when {
                    c == '\\' -> {
                        i++
                    }

                    inClass -> {
                        if (c == ']') inClass = false
                    }

                    c == '[' -> {
                        inClass = true
                        if (pattern.getOrNull(i + 1) == '^') i++
                        if (pattern.getOrNull(i + 1) == ']') i++
                    }

                    c == '{' -> {
                        val q = quantifier.matchAt(pattern, i) ?: return true
                        i = q.range.last
                    }

                    c == '}' -> {
                        return true
                    }
                }
                i++
            }
            return false
        }
    }
}
