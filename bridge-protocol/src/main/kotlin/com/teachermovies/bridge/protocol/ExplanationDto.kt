package com.teachermovies.bridge.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `text` of the [BridgeJobResultDto.Done] answering an `explain` job (#291, ADR-0005 §7): the
 * short Castilian explanation of the captured line, as the JSON of this class. [promptVersion] names
 * the system prompt that wrote it -- the TV keys its explanation cache by it (#274, #292), so a new
 * prompt starts a fresh set of answers.
 *
 * [resumen] is at most [MAX_SUMMARY_CHARS] chars, [puntos] at most [MAX_POINTS], and
 * [diferenciaSubtitulo] -- why the aligned Spanish subtitle says something other than the English
 * line -- is null when there is nothing to say or no Spanish line was given. Lengths are in Unicode
 * code points. The bridge never posts one that breaks these limits.
 */
@Serializable
data class ExplanationDto(
    val promptVersion: String,
    val resumen: String,
    val puntos: List<ExplanationPointDto>,
    @SerialName("diferencia_subtitulo")
    val diferenciaSubtitulo: String?,
) {
    companion object {
        const val MAX_SUMMARY_CHARS: Int = 160
        const val MAX_POINTS: Int = 3
        const val MAX_EXPRESSION_CHARS: Int = 80
        const val MAX_POINT_CHARS: Int = 140
        const val MAX_DIFFERENCE_CHARS: Int = 160
    }
}

/** One idiom, phrasal verb, slang word or grammar point of the line: [explicacion] ≤ [ExplanationDto.MAX_POINT_CHARS]. */
@Serializable
data class ExplanationPointDto(
    val expresion: String,
    val explicacion: String,
)
