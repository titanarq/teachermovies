package com.teachermovies.bridge.claude

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The tolerant extraction step of ADR-0005 §3 (#291): the first JSON object in a Claude reply,
 * whatever surrounds it -- a Markdown code fence, a sentence before or after -- or null when the
 * reply holds none that parses. Validating the object is the handler's job.
 */
object JsonReply {
    fun extractObject(text: String): JsonObject? {
        var start = text.indexOf('{')
        while (start >= 0) {
            val end = matchingBrace(text, start)
            if (end < 0) return null
            parse(text.substring(start, end + 1))?.let { return it }
            start = text.indexOf('{', start + 1)
        }
        return null
    }

    private fun parse(candidate: String): JsonObject? =
        try {
            JSON.parseToJsonElement(candidate) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    /** Index of the `}` closing the `{` at [open], skipping braces inside strings; -1 if unclosed. */
    private fun matchingBrace(
        text: String,
        open: Int,
    ): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in open until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> {
                    inString = true
                }

                '{' -> {
                    depth += 1
                }

                '}' -> {
                    depth -= 1
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private val JSON = Json
}
