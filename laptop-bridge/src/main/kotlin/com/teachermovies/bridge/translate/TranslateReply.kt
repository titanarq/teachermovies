package com.teachermovies.bridge.translate

import com.teachermovies.bridge.claude.JsonReply
import com.teachermovies.bridge.protocol.BridgeJobProtocol
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Extraction and validation of one translate reply (#286): [parse] turns Claude's text into the
 * Spanish line it translated, or says -- in Spanish, in its own words, never quoting the reply --
 * what broke the schema, which is what the one re-ask tells Claude.
 *
 * Tolerated: text or a code fence around the object, unknown keys (dropped), surrounding whitespace
 * in the translation (trimmed). Everything else is [Result.Invalid].
 *
 * [MAX_CHARS] is this handler's own bound (the issue fixed none): twice what the protocol allows the
 * English line of a job, because Spanish runs longer than English and the answer is one subtitle
 * line, not a paragraph. A longer reply is a model that explained instead of translating, and the
 * re-ask says so.
 */
object TranslateReply {
    /** Longest accepted translation, in Unicode code points. */
    const val MAX_CHARS: Int = 2 * BridgeJobProtocol.MAX_LINE_CHARS

    sealed interface Result {
        data class Valid(
            val translation: String,
        ) : Result

        data class Invalid(
            val problem: String,
        ) : Result
    }

    fun parse(text: String): Result {
        val reply = JsonReply.extractObject(text) ?: return Result.Invalid("no contiene ningún objeto JSON")
        return translation(reply[TRANSLATION])
    }

    private fun translation(element: JsonElement?): Result {
        if (element == null || element == JsonNull) return Result.Invalid(MISSING)
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) return Result.Invalid("\"$TRANSLATION\" no es un texto")
        val value = primitive.content.trim()
        if (value.isEmpty()) return Result.Invalid(MISSING)
        val chars = value.codePointCount(0, value.length)
        if (chars > MAX_CHARS) {
            return Result.Invalid("\"$TRANSLATION\" tiene $chars caracteres y el máximo es $MAX_CHARS")
        }
        return Result.Valid(value)
    }

    private const val TRANSLATION = "translation"
    private const val MISSING = "falta \"translation\" o está vacío"
}
