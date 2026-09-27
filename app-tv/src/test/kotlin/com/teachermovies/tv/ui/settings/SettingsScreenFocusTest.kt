package com.teachermovies.tv.ui.settings

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.teachermovies.http.ServerState
import com.teachermovies.tv.ui.TeacherMoviesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * D-pad focus order of Configuración around the laptop bridge rows (#289), driven with real key
 * events on the JVM, on a 1080p TV's 960x540 dp screen (the default phone-sized one squeezes the
 * right column to zero width). A plain [Application] stands in for `TeacherMoviesApp`, whose
 * container would load jlibtorrent and libVLC.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
class SettingsScreenFocusTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    /** Declares the empty host activity to Robolectric before [compose] launches it. */
    private val hostActivity =
        object : ExternalResource() {
            override fun before() {
                val app = ApplicationProvider.getApplicationContext<Application>()
                shadowOf(app.packageManager)
                    .addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
            }
        }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hostActivity).around(compose)

    private val volume = VolumeRow("primary", "Almacenamiento interno", 5_000_000_000, 16_000_000_000, removable = false)
    private val paired =
        SettingsUiState(
            httpPort = 8787,
            volumes = listOf(volume),
            selectedVolumeId = "primary",
            pin = "482916",
            serverState = ServerState.Running(8787),
            bridgeConnected = true,
            bridgePaired = true,
            bridgeName = "portatil-manuel",
        )

    private var state by mutableStateOf(paired)
    private var forgets = 0

    private fun show(initial: SettingsUiState) {
        state = initial
        compose.setContent {
            TeacherMoviesTheme {
                SettingsScreen(
                    uiState = state,
                    onPortChange = {},
                    onSelectVolume = {},
                    onAutostartChange = {},
                    onForgetBridge = {
                        forgets++
                        state = state.copy(bridgeConnected = false, bridgePaired = false, bridgeName = null)
                    },
                )
            }
        }
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private val port: SemanticsNodeInteraction get() = compose.onNodeWithText("8787")
    private val volumeRow: SemanticsNodeInteraction get() = compose.onNodeWithText("Almacenamiento interno")
    private val autostart: SemanticsNodeInteraction get() = compose.onNodeWithText("Arrancar al encender la TV")
    private val forget: SemanticsNodeInteraction get() = compose.onNodeWithText("Olvidar portátil")

    /** Starts on the port field, as the section's entry focus does. */
    private fun focusPort() {
        // A key press first puts the owner in keyboard (D-pad) input mode, as on the TV.
        press(Key.DirectionDown)
        port.requestFocus()
        compose.waitForIdle()
        port.assertIsFocused()
    }

    @Test
    fun connectedBridgeShowsItsNameAndNoApiKeyField() {
        show(paired)

        compose.onNodeWithText("Portátil (Claude): Conectado · portatil-manuel").assertExists()
        forget.assertExists()
        compose.onNodeWithText("Clave API de traducción (Anthropic)").assertDoesNotExist()
        compose.onNodeWithText("No configurada").assertDoesNotExist()
        compose.onNodeWithText("Configurada").assertDoesNotExist()
    }

    @Test
    fun connectedBridgeWithoutANameSaysConnected() {
        show(paired.copy(bridgeName = null))

        compose.onNodeWithText("Portátil (Claude): Conectado").assertExists()
    }

    @Test
    fun noBridgeSaysNotConnectedAndOffersNoForget() {
        show(paired.copy(bridgeConnected = false, bridgePaired = false, bridgeName = null))

        compose.onNodeWithText("Portátil (Claude): No conectado").assertExists()
        forget.assertDoesNotExist()
    }

    @Test
    fun pairedButOfflineBridgeSaysNotConnectedAndCanStillBeForgotten() {
        show(paired.copy(bridgeConnected = false))

        compose.onNodeWithText("Portátil (Claude): No conectado").assertExists()
        forget.assertExists()
    }

    @Test
    fun dPadWalksPortVolumeAutostartForgetAndBack() {
        show(paired)
        focusPort()

        press(Key.DirectionRight)
        volumeRow.assertIsFocused()
        press(Key.DirectionDown)
        autostart.assertIsFocused()
        press(Key.DirectionDown)
        forget.assertIsFocused()
        // The status line is plain text: DOWN from the last row stays put.
        press(Key.DirectionDown)
        forget.assertIsFocused()
        press(Key.DirectionUp)
        autostart.assertIsFocused()
        press(Key.DirectionDown)
        forget.assertIsFocused()
        press(Key.DirectionLeft)
        port.assertIsFocused()
    }

    @Test
    fun okOnForgetForgetsTheBridgeAndLeavesFocusOnTheAutostartSwitch() {
        show(paired)
        focusPort()
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        forget.assertIsFocused()

        press(Key.DirectionCenter)

        assertEquals(1, forgets)
        forget.assertDoesNotExist()
        compose.onNodeWithText("Portátil (Claude): No conectado").assertExists()
        autostart.assertIsFocused()
    }

    @Test
    fun withNoBridgeDownFromTheAutostartSwitchStaysOnIt() {
        show(paired.copy(bridgeConnected = false, bridgePaired = false, bridgeName = null))
        focusPort()
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        autostart.assertIsFocused()

        press(Key.DirectionDown)
        autostart.assertIsFocused()
        press(Key.DirectionLeft)
        port.assertIsFocused()
    }
}
