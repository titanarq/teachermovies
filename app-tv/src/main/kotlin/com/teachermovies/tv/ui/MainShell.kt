package com.teachermovies.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.teachermovies.tv.ui.downloads.DownloadsScreen

/**
 * The shell: a tab row over the selected section. State is hoisted, so the shell is a pure function
 * of [uiState] and every navigation event leaves through [onSelect].
 *
 * Focus, which is part of "done" for a TV screen (AGENTS.md): launch focuses the tab of the
 * selected section (Biblioteca, on a fresh launch), LEFT and RIGHT walk the tabs (Compose's own
 * focus search between them -- `TabRow` adds no key handling), BACK from a section's content
 * returns focus to that section's tab, and BACK from the tab row has nothing left to intercept and
 * so reaches the activity, which finishes.
 *
 * A section that needs a ViewModel arrives as a slot ([libraryContent], [settingsContent]), so the
 * shell stays free of the object graph: `MainActivity` binds them.
 */
@Composable
fun MainShell(
    uiState: MainUiState,
    onSelect: (Destination) -> Unit,
    libraryContent: @Composable (Modifier) -> Unit,
    settingsContent: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val destinations = Destination.entries
    val tabFocusRequesters = remember { destinations.associateWith { FocusRequester() } }
    var sectionHasFocus by remember { mutableStateOf(false) }

    BackHandler(enabled = sectionHasFocus) {
        tabFocusRequesters.getValue(uiState.selected).requestFocus()
    }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = destinations.indexOf(uiState.selected)) {
            destinations.forEach { destination ->
                Tab(
                    selected = destination == uiState.selected,
                    onFocus = { onSelect(destination) },
                    onClick = { onSelect(destination) },
                    modifier = Modifier.focusRequester(tabFocusRequesters.getValue(destination)),
                ) {
                    Text(text = stringResource(destination.titleRes))
                }
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { sectionHasFocus = it.hasFocus },
        ) {
            when (uiState.selected) {
                Destination.Library -> libraryContent(Modifier.fillMaxSize())
                Destination.Downloads -> DownloadsScreen(modifier = Modifier.fillMaxSize())
                Destination.Settings -> settingsContent(Modifier.fillMaxSize())
            }
        }
    }

    // Launch has to land somewhere the D-pad can move on from, so the selected section's tab asks
    // for focus: Biblioteca on a fresh launch, and still the selected one if the activity is
    // recreated with a section already chosen -- focusing the first tab instead would re-select it.
    LaunchedEffect(Unit) { tabFocusRequesters.getValue(uiState.selected).requestFocus() }
}
