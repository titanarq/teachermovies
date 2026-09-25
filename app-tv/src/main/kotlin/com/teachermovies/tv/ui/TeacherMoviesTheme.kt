package com.teachermovies.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** The app's one colour scheme: tv-material's dark scheme, like the TV home screen around it. */
val TeacherMoviesColorScheme: ColorScheme = darkColorScheme()

/**
 * The app theme (#225): wraps everything `MainActivity` shows. It sets the dark tv-material
 * [TeacherMoviesColorScheme], paints its `background` edge to edge, and makes `onBackground` the
 * default content colour, so a `Text` outside any `Surface` is light on dark instead of
 * tv-material's default black. Screens take colours from `MaterialTheme.colorScheme`; only the
 * player's overlays, drawn over video, use fixed white-on-black.
 */
@Composable
fun TeacherMoviesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TeacherMoviesColorScheme) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                content()
            }
        }
    }
}
