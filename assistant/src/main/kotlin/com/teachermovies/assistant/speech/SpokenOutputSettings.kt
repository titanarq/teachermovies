package com.teachermovies.assistant.speech

/**
 * Which of the assistant's spoken answers the learner has turned back on (#294). The two lines
 * are silent by default (ADR-0005 §10 defers TTS); the explanation is spoken by default, in English.
 *
 * The three answers are independent, because turning the Spanish line on says nothing about whether
 * the English one is wanted too. Each is read at the moment of speaking, so a change applies from
 * the next line on without reopening the player; what the setting gates is only the voice, never the
 * text on screen.
 */
data class SpokenOutputSettings(
    /** Say the captured line again in English. */
    val englishLine: Boolean = false,
    /** Say the Spanish line, whether it came from an aligned subtitle or from a translation. */
    val spanishLine: Boolean = false,
    /** Say the English explanation of the line (ADR-0005 §7). */
    val explanations: Boolean = true,
)
