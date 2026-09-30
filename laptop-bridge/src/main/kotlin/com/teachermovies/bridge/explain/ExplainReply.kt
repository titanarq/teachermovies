package com.teachermovies.bridge.explain

import com.teachermovies.bridge.claude.JsonReply
import com.teachermovies.bridge.protocol.ExplanationDto
import com.teachermovies.bridge.protocol.ExplanationPointDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Extraction and validation of one explain reply (#291): [parse] turns Claude's text into an
 * [ExplanationDto] tagged with [ExplainPrompt.VERSION], or says -- in Spanish, in its own words,
 * never quoting the reply -- what broke the schema, which is what the one re-ask tells Claude.
 *
 * Tolerated: text or a code fence around the object, unknown keys (dropped), surrounding whitespace
 * in a string (trimmed), a missing or blank `diferencia_subtitulo` (null).
 * Everything else outside [ExplanationDto]'s limits is [Result.Invalid].
 */
object ExplainReply {
    sealed interface Result {
        data class Valid(
            val explanation: ExplanationDto,
        ) : Result

        data class Invalid(
            val problem: String,
        ) : Result
    }

    fun parse(text: String): Result {
        val reply = JsonReply.extractObject(text) ?: return Result.Invalid("no contiene ningún objeto JSON")
        return try {
            Result.Valid(explanation(reply))
        } catch (e: InvalidReply) {
            Result.Invalid(checkNotNull(e.message))
        }
    }

    private fun explanation(reply: JsonObject): ExplanationDto {
        val resumen = requiredString(reply[SUMMARY], SUMMARY, ExplanationDto.MAX_SUMMARY_CHARS)
        val puntos =
            when (val points = reply[POINTS]) {
                null, JsonNull -> emptyList()
                is JsonArray -> points
                else -> invalid("\"$POINTS\" no es una lista")
            }
        if (puntos.size > ExplanationDto.MAX_POINTS) {
            invalid("\"$POINTS\" tiene ${puntos.size} elementos y el máximo es ${ExplanationDto.MAX_POINTS}")
        }
        val difference = optionalString(reply[DIFFERENCE], DIFFERENCE, ExplanationDto.MAX_DIFFERENCE_CHARS)
        return ExplanationDto(
            promptVersion = ExplainPrompt.VERSION,
            resumen = resumen,
            puntos = puntos.mapIndexed { i, point -> point(point, i + 1) },
            diferenciaSubtitulo = difference,
        )
    }

    private fun point(
        element: JsonElement,
        position: Int,
    ): ExplanationPointDto {
        val point = element as? JsonObject ?: invalid("el punto $position no es un objeto")
        return ExplanationPointDto(
            expresion =
                requiredString(
                    point[EXPRESSION],
                    "$EXPRESSION del punto $position",
                    ExplanationDto.MAX_EXPRESSION_CHARS,
                ),
            explicacion =
                requiredString(point[EXPLANATION], "$EXPLANATION del punto $position", ExplanationDto.MAX_POINT_CHARS),
        )
    }

    private fun requiredString(
        element: JsonElement?,
        name: String,
        maxChars: Int,
    ): String = optionalString(element, name, maxChars) ?: invalid("falta \"$name\" o está vacío")

    /** The trimmed string in [element], null when absent, JSON null or blank. */
    private fun optionalString(
        element: JsonElement?,
        name: String,
        maxChars: Int,
    ): String? {
        if (element == null || element == JsonNull) return null
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) invalid("\"$name\" no es un texto")
        val value = primitive.content.trim()
        if (value.isEmpty()) return null
        val chars = value.codePointCount(0, value.length)
        if (chars > maxChars) invalid("\"$name\" tiene $chars caracteres y el máximo es $maxChars")
        return value
    }

    private fun invalid(problem: String): Nothing = throw InvalidReply(problem)

    private class InvalidReply(
        problem: String,
    ) : Exception(problem)

    private const val SUMMARY = "resumen"
    private const val POINTS = "puntos"
    private const val EXPRESSION = "expresion"
    private const val EXPLANATION = "explicacion"
    private const val DIFFERENCE = "diferencia_subtitulo"
}
