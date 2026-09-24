package com.teachermovies.tv.ui.downloads

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.teachermovies.tv.R
import com.teachermovies.tv.ui.PlaceholderScreen

/** Active and finished downloads. #46 replaces the placeholder with them. */
@Composable
fun DownloadsScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(title = stringResource(R.string.destination_downloads), modifier = modifier)
}
