package com.teachermovies.tv.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * #224: the port changes only through the editor's confirm, which goes through [portFromEditor]
 * and then [SettingsViewModel.changePort]; D-pad UP/DOWN on the row no longer change it.
 */
class PortEditorInputTest {
    @Test
    fun `a typed number is the confirmed port`() {
        assertEquals(8788, portFromEditor("8788"))
        assertEquals(1024, portFromEditor(" 1024 "))
    }

    @Test
    fun `an empty or non-numeric entry maps to an invalid port the ViewModel rejects`() {
        listOf("", "   ", "80a", "99999999999").forEach { typed ->
            assertFalse("'$typed'", portFromEditor(typed) in VALID_HTTP_PORTS)
        }
    }
}
