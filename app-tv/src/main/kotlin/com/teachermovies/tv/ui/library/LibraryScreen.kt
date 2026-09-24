package com.teachermovies.tv.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.teachermovies.tv.R
import com.teachermovies.tv.ui.PlaceholderScreen

/** The stored movies, listed and playable. #45 replaces the placeholder with them. */
@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(title = stringResource(R.string.destination_library), modifier = modifier)
}
