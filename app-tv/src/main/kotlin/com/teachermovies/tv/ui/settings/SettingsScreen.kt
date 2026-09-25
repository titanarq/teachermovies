package com.teachermovies.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.teachermovies.tv.R
import com.teachermovies.tv.ui.server.ServerPinAndStatus

/**
 * The Configuración section bound to its [SettingsViewModel]. Every time the section is shown the
 * volume list is re-read, so a USB drive plugged in since the last visit appears.
 */
@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshVolumes() }
    SettingsScreen(
        uiState = uiState,
        onPortChange = viewModel::changePort,
        onSelectVolume = viewModel::selectVolume,
        onAutostartChange = viewModel::setAutostartOnBoot,
        onTranslationApiKeyChange = viewModel::changeTranslationApiKey,
        modifier = modifier,
    )
}

/**
 * HTTP port on the left, with the server address, pairing PIN and (when not running) server state
 * under it as plain, unfocusable text; download volume list on the right, with the "Arrancar al
 * encender la TV" switch (#126) and the "Clave API de traducción (Anthropic)" field (#212) under it.
 *
 * Focus: DOWN from the tab row enters on the port field (then on whatever last had focus in the
 * section). On the port field UP/DOWN change the port by one -- so they do not move focus -- OK
 * opens a numeric text field, and RIGHT moves to the volume list. In the list UP/DOWN walk the
 * volumes, OK selects one, LEFT returns to the port field and UP from the first volume reaches the
 * tab row. DOWN from the last volume reaches the autostart switch (RIGHT from the port field lands
 * on it directly when there is no volume); OK toggles it, UP goes back to the list and LEFT to the
 * port field. DOWN from the switch reaches the translation-key field; UP goes back to the switch and
 * LEFT to the port field. The key field shows only whether a key is set, never the key; OK opens a
 * masked text field holding the stored key, where OK/Done saves (an empty field clears the key).
 * BACK anywhere in the section returns to the tab row (the shell's handler); while a text field is
 * open BACK only closes it, without saving, and so does moving focus out of it.
 */
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onPortChange: (Int) -> Unit,
    onSelectVolume: (String) -> Unit,
    onAutostartChange: (Boolean) -> Unit,
    onTranslationApiKeyChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val portRequester = remember { FocusRequester() }

    Row(
        modifier = modifier.padding(48.dp).focusRestorer(portRequester).focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(64.dp),
    ) {
        Column(
            modifier = Modifier.width(320.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = stringResource(R.string.settings_port_title), style = MaterialTheme.typography.titleMedium)
            PortField(port = uiState.httpPort, onPortChange = onPortChange, focusRequester = portRequester)
            Text(
                text = stringResource(R.string.settings_port_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            if (uiState.portError) {
                Text(
                    text = stringResource(R.string.settings_port_error, VALID_HTTP_PORTS.first, VALID_HTTP_PORTS.last),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(text = stringResource(R.string.settings_server_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = uiState.serverUrl ?: stringResource(R.string.first_run_no_network),
                style = MaterialTheme.typography.bodyLarge,
            )
            ServerPinAndStatus(
                pin = uiState.pin,
                serverState = uiState.serverState,
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(R.string.settings_volume_title), style = MaterialTheme.typography.titleMedium)
            if (uiState.volumeMissing) {
                Text(
                    text = stringResource(R.string.settings_volume_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (uiState.volumes.isEmpty()) {
                Text(text = stringResource(R.string.settings_volume_none), style = MaterialTheme.typography.bodyMedium)
            }
            uiState.volumes.forEach { row ->
                VolumeItem(row = row, selected = row.id == uiState.selectedVolumeId, onSelect = onSelectVolume)
            }
            Text(
                text = stringResource(R.string.settings_autostart_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            AutostartItem(checked = uiState.autostartOnBoot, onChange = onAutostartChange)
            Text(
                text = stringResource(R.string.settings_translation_key_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            TranslationKeyField(apiKey = uiState.translationApiKey, onKeyChange = onTranslationApiKeyChange)
            Text(
                text = stringResource(R.string.settings_translation_key_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(560.dp),
            )
        }
    }
}

@Composable
private fun PortField(
    port: Int,
    onPortChange: (Int) -> Unit,
    focusRequester: FocusRequester,
) {
    var editing by remember { mutableStateOf(false) }
    var returnFocus by remember { mutableStateOf(false) }

    if (!editing) {
        Surface(
            onClick = { editing = true },
            modifier =
                Modifier
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionUp -> {
                                onPortChange(port + 1)
                                true
                            }

                            Key.DirectionDown -> {
                                onPortChange(port - 1)
                                true
                            }

                            else -> {
                                false
                            }
                        }
                    },
        ) {
            Text(
                text = port.toString(),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    } else {
        PortTextField(
            initial = port,
            onFinish = { typed ->
                // Anything that is not a number is as invalid as an out-of-range one: 0 is outside
                // the valid range, so the ViewModel rejects it and flags the error.
                if (typed != null) onPortChange(typed.toIntOrNull() ?: 0)
                editing = false
                returnFocus = true
            },
        )
    }

    // Closing the text field removes the focused node; focus goes back to the value it edited.
    LaunchedEffect(editing) {
        if (!editing && returnFocus) {
            focusRequester.requestFocus()
            returnFocus = false
        }
    }
}

/** [onFinish] gets the typed text on OK/Done, or null when the edit is abandoned. */
@Composable
private fun PortTextField(
    initial: Int,
    onFinish: (String?) -> Unit,
) {
    val initialText = initial.toString()
    var value by remember { mutableStateOf(TextFieldValue(initialText, selection = TextRange(0, initialText.length))) }
    val fieldRequester = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    var okPressed by remember { mutableStateOf(false) }

    // Registered after the shell's handler, so it wins while the field is open.
    BackHandler { onFinish(null) }

    BasicTextField(
        value = value,
        onValueChange = { next ->
            if (next.text.length <= MAX_PORT_DIGITS && next.text.all(Char::isDigit)) value = next
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onFinish(value.text) }),
        textStyle = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier =
            Modifier
                .focusRequester(fieldRequester)
                .onPreviewKeyEvent { event ->
                    // The remote's OK is DPAD_CENTER, which a text field does not treat as Done. Only
                    // a press that started here counts: the KeyUp of the OK that opened the field
                    // must not close it again.
                    val isOk =
                        event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (isOk && event.type == KeyEventType.KeyDown) okPressed = true
                    if (isOk && event.type == KeyEventType.KeyUp && okPressed) onFinish(value.text)
                    isOk
                }.onFocusChanged { state ->
                    // D-pad moving focus away abandons the edit, like BACK.
                    if (state.isFocused) {
                        hadFocus = true
                    } else if (hadFocus) {
                        onFinish(null)
                    }
                }.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .width(160.dp),
    )

    LaunchedEffect(Unit) { fieldRequester.requestFocus() }
}

@Composable
private fun VolumeItem(
    row: VolumeRow,
    selected: Boolean,
    onSelect: (String) -> Unit,
) {
    ListItem(
        selected = selected,
        onClick = { onSelect(row.id) },
        headlineContent = { Text(text = row.label) },
        supportingContent = {
            Text(
                text =
                    stringResource(
                        R.string.settings_volume_space,
                        SpaceFormat.freeGb(row.freeBytes),
                        SpaceFormat.totalGb(row.totalBytes),
                    ),
            )
        },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.width(560.dp),
    )
}

/** The whole row is the focus target; OK flips the switch, which only mirrors the setting. */
@Composable
private fun AutostartItem(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        selected = false,
        onClick = { onChange(!checked) },
        headlineContent = { Text(text = stringResource(R.string.settings_autostart_label)) },
        supportingContent = { Text(text = stringResource(R.string.settings_autostart_hint)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.width(560.dp),
    )
}

/**
 * The translation API key (#212), shaped like [PortField]: a focusable value that only says whether
 * a key is set, and OK opens [TranslationKeyTextField] to edit it. The key itself is never drawn in
 * clear and never logged.
 */
@Composable
private fun TranslationKeyField(
    apiKey: String,
    onKeyChange: (String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var returnFocus by remember { mutableStateOf(false) }

    if (!editing) {
        Surface(
            onClick = { editing = true },
            modifier = Modifier.focusRequester(focusRequester).width(560.dp),
        ) {
            val label =
                if (apiKey.isEmpty()) R.string.settings_translation_key_unset else R.string.settings_translation_key_set
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    } else {
        TranslationKeyTextField(
            initial = apiKey,
            onFinish = { typed ->
                if (typed != null) onKeyChange(typed)
                editing = false
                returnFocus = true
            },
        )
    }

    // Closing the text field removes the focused node; focus goes back to the value it edited.
    LaunchedEffect(editing) {
        if (!editing && returnFocus) {
            focusRequester.requestFocus()
            returnFocus = false
        }
    }
}

/**
 * Masked single-line field holding [initial], all of it selected so a paste replaces it.
 * [onFinish] gets the typed text on OK/Done, or null when the edit is abandoned.
 */
@Composable
private fun TranslationKeyTextField(
    initial: String,
    onFinish: (String?) -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    val fieldRequester = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    var okPressed by remember { mutableStateOf(false) }

    // Registered after the shell's handler, so it wins while the field is open.
    BackHandler { onFinish(null) }

    BasicTextField(
        value = value,
        onValueChange = { next -> if (next.text.length <= MAX_API_KEY_LENGTH) value = next },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
        keyboardActions = KeyboardActions(onDone = { onFinish(value.text) }),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier =
            Modifier
                .focusRequester(fieldRequester)
                .onPreviewKeyEvent { event ->
                    // Same OK handling as PortTextField: DPAD_CENTER is not Done for a text field,
                    // and only a press that started here counts.
                    val isOk =
                        event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (isOk && event.type == KeyEventType.KeyDown) okPressed = true
                    if (isOk && event.type == KeyEventType.KeyUp && okPressed) onFinish(value.text)
                    isOk
                }.onFocusChanged { state ->
                    // D-pad moving focus away abandons the edit, like BACK.
                    if (state.isFocused) {
                        hadFocus = true
                    } else if (hadFocus) {
                        onFinish(null)
                    }
                }.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .width(512.dp),
    )

    LaunchedEffect(Unit) { fieldRequester.requestFocus() }
}

private const val MAX_PORT_DIGITS = 5

/** Far longer than any Anthropic key; only bounds what a stray paste can put in the field. */
private const val MAX_API_KEY_LENGTH = 256
