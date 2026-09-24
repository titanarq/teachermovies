package com.teachermovies.tv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.teachermovies.tv.R
import com.teachermovies.tv.ui.PlaceholderScreen

/** Server address, PIN, volume and free space. #70 and #72 replace the placeholder with them. */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(title = stringResource(R.string.destination_settings), modifier = modifier)
}
