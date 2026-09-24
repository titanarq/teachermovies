package com.teachermovies.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.tv.material3.Text

/**
 * What a section shows until the issue that fills it lands: its own title, centred. The container
 * holds no focusable element, so focus stays in the tab row until then.
 */
@Composable
internal fun PlaceholderScreen(
    title: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(text = title)
    }
}
