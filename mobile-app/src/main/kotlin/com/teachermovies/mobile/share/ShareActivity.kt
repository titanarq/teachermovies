package com.teachermovies.mobile.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teachermovies.mobile.MainActivity
import com.teachermovies.mobile.MobileApp

/**
 * The target of the manifest's two share intent filters (#199): `ACTION_SEND` of `text/plain`, what
 * any app's share sheet offers as `Movie Assistant`, and `ACTION_VIEW` of a `magnet:` link. This is
 * the only place in the app that reads a share `Intent`: [SharedText] turns the three strings it
 * carries into the text to send, and [ShareViewModel] does its one send in `init`, so rotating the
 * phone -- a new activity over the same ViewModel -- cannot send the magnet twice. Whether the
 * shared text holds a magnet at all is `MagnetSender`'s call, reported as any other outcome.
 *
 * The window is a dialog (`Theme.MovieAssistant.Share`): what the user sees is [ShareScreen] over
 * the app they shared from, and each of its buttons ends this activity.
 */
class ShareActivity : ComponentActivity() {
    // Read off the `Intent` once, as three strings, so nothing above this sees an Android type.
    private val sharedText: String? by lazy {
        SharedText.from(
            action = intent?.action,
            extraText = intent?.getStringExtra(Intent.EXTRA_TEXT),
            dataString = intent?.dataString,
        )
    }

    private val shareViewModel: ShareViewModel by viewModels {
        ShareViewModel.Factory(
            magnetSender = (application as MobileApp).container.magnetSender,
            text = sharedText,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val state by shareViewModel.uiState.collectAsStateWithLifecycle()
                ShareScreen(
                    state = state,
                    onClose = ::finish,
                    onOpenApp = ::openMainActivity,
                )
            }
        }
    }

    /** `ABRIR MOVIE ASSISTANT`: the launcher app, where a TV can be paired; this dialog is over. */
    private fun openMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
