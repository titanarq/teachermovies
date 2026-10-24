package com.teachermovies.tv.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Sets or clears "keep the screen on" for the window; the Android window is behind this. */
fun interface KeepScreenOnSetter {
    fun set(keepOn: Boolean)
}

/**
 * Keeps the screen on (no screensaver, no screen-off) only while a video is actually playing.
 * [apply] is idempotent; [release] always clears the flag (paused, ended, left composition).
 */
class KeepScreenOnController(
    private val setter: KeepScreenOnSetter,
) {
    fun apply(isPlaying: Boolean) = setter.set(isPlaying)

    fun release() = setter.set(false)
}

/**
 * Holds `FLAG_KEEP_SCREEN_ON` on the activity window while [isPlaying] is true; clears it when it
 * turns false and when this composable leaves composition. Does nothing without an [Activity].
 */
@Composable
fun KeepScreenOnWhilePlaying(isPlaying: Boolean) {
    val window = LocalContext.current.findActivity()?.window ?: return
    val controller =
        remember(window) {
            KeepScreenOnController { keepOn ->
                if (keepOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    DisposableEffect(controller, isPlaying) {
        controller.apply(isPlaying)
        onDispose { controller.release() }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
