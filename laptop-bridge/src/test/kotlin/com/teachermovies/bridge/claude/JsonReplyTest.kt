package com.teachermovies.bridge.claude

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tolerant extraction of the first JSON object in a reply (#291, ADR-0005 §3). */
class JsonReplyTest {
    @Test
    fun `a bare object is returned as is`() {
        assertEquals(JsonPrimitive("x"), JsonReply.extractObject("""{"a":"x"}""")!!["a"])
    }

    @Test
    fun `a code fence and text around the object are ignored`() {
        val text = "Aquí tienes:\n```json\n{\"a\": \"x\", \"b\": {\"c\": 1}}\n```\nEspero que ayude."
        assertEquals(JsonPrimitive(1), JsonReply.extractObject(text)!!["b"]!!.jsonObject["c"])
    }

    @Test
    fun `braces and quotes inside strings do not end the object`() {
        val text = """{"a": "un } raro y \" comillas {", "b": 2}"""
        assertEquals(JsonPrimitive(2), JsonReply.extractObject(text)!!["b"])
    }

    @Test
    fun `a brace that opens no valid object is skipped for the next one`() {
        assertEquals(JsonPrimitive("x"), JsonReply.extractObject("""usa {llaves} así: {"a":"x"}""")!!["a"])
    }

    @Test
    fun `no object, an unclosed one or a bare array is null`() {
        assertNull(JsonReply.extractObject("lo siento, no puedo"))
        assertNull(JsonReply.extractObject("""{"a": "x""""))
        assertNull(JsonReply.extractObject("""["a"]"""))
        assertNull(JsonReply.extractObject(""))
    }
}
