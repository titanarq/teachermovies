package com.teachermovies.assistant.explanation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One explanation as the panel renders it (#293): a short Castilian [summary], up to
 * [MAX_POINTS] [points] on the expressions that matter, and [subtitleNote], why the Spanish subtitle
 * says something different, when the bridge had one. [promptVersion] is the bridge prompt that wrote
 * it, when the document says so.
 */
data class Explanation(
    val summary: String,
    val points: List<ExplanationPoint>,
    val subtitleNote: String?,
    val promptVersion: String?,
) {
    /**
     * The text to say aloud: the summary, then each point's expression and explanation, then the
     * subtitle note if any; blank parts are skipped and the parts are joined with a space.
     */
    fun spokenText(): String =
        buildList {
            add(summary)
            points.forEach {
                add(it.expression)
                add(it.explanation)
            }
            subtitleNote?.let(::add)
        }.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    companion object {
        /** Points the panel shows at most (#291's schema). */
        const val MAX_POINTS = 3

        /**
         * Reads the document the bridge's explain handler returns (#291):
         * `{"resumen", "puntos": [{"expresion", "explicacion"}], "diferencia_subtitulo": string|null}`,
         * plus an optional `promptVersion` (or `prompt_version`) tag. The bridge validates it before
         * answering; this side is tolerant but never shows an empty panel: a point missing either
         * field is dropped, points past [MAX_POINTS] are dropped, a blank note is null, and a document
         * that is not JSON, not an object or has no non-blank `resumen` is null.
         */
        fun parse(document: String): Explanation? {
            val parsed =
                try {
                    Json.parseToJsonElement(document)
                } catch (e: IllegalArgumentException) {
                    // kotlinx.serialization's SerializationException is an IllegalArgumentException.
                    return null
                }
            val root = parsed as? JsonObject ?: return null
            val summary = root.text("resumen") ?: return null
            val points =
                (root["puntos"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { it.point() }
                    .take(MAX_POINTS)
            return Explanation(
                summary = summary,
                points = points,
                subtitleNote = root.text("diferencia_subtitulo"),
                promptVersion = root.text("promptVersion") ?: root.text("prompt_version"),
            )
        }

        private fun JsonElement.point(): ExplanationPoint? {
            val point = this as? JsonObject ?: return null
            return ExplanationPoint(
                expression = point.text("expresion") ?: return null,
                explanation = point.text("explicacion") ?: return null,
            )
        }

        /** The trimmed, non-blank string at [key], or null for anything else (absent, null, a number). */
        private fun JsonObject.text(key: String): String? {
            val value = this[key] as? JsonPrimitive ?: return null
            if (value is JsonNull || !value.isString) return null
            return value.content.trim().takeIf { it.isNotEmpty() }
        }
    }
}

/** One expression of the line ([expression]) and what it means here ([explanation]), in Spanish. */
data class ExplanationPoint(
    val expression: String,
    val explanation: String,
)
