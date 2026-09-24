package com.teachermovies.tv.ui.player

import com.teachermovies.assistant.TranslationFailure
import com.teachermovies.assistant.TranslationUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationLineTest {
    @Test
    fun idleDrawsNothing() {
        assertNull(translationLine(TranslationUiState.Idle))
    }

    @Test
    fun eachStateHasItsSpanishLine() {
        assertEquals("Traduciendo…", translationLine(TranslationUiState.Loading))
        assertEquals("Hola.", translationLine(TranslationUiState.Ready("Hola.")))
        assertEquals(
            "Sin conexión para traducir",
            translationLine(TranslationUiState.Failed(TranslationFailure.OFFLINE)),
        )
        assertEquals(
            "Traducción no disponible",
            translationLine(TranslationUiState.Failed(TranslationFailure.UNAVAILABLE)),
        )
    }

    @Test
    fun theHintLineNamesEveryOverlayKey() {
        assertEquals("OK Repetir · DERECHA Escuchar · IZQUIERDA Traducir · ATRÁS Cerrar", ASSISTANT_HINT)
    }
}
