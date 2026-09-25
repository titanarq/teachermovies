package com.teachermovies.mobile.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.teachermovies.mobile.send.MagnetSender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Sends one shared magnet to the paired TV and reports what came of it (#199).
 *
 * [text] is what the other app handed over, already read off the `Intent` by `ShareActivity` with
 * [SharedText], and it may be `null`: the send starts in `init`, so this ViewModel -- and not the
 * activity -- is what decides how many times it happens. A ViewModel is created once per activity
 * instance and survives a configuration change, which is what makes rotating the phone send the
 * magnet exactly once: `ShareActivity` has no `send` of its own to call twice, and nothing here is
 * gated on a screen collecting [uiState], so a dialog that arrives late still finds the outcome.
 * Every outcome is a [com.teachermovies.mobile.send.SendOutcome]; nothing here logs the token.
 */
class ShareViewModel(
    magnetSender: MagnetSender,
    text: String?,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ShareUiState>(ShareUiState.Sending)

    /** [ShareUiState.Sending] until the one send is over, then [ShareUiState.Done] with its outcome. */
    val uiState: StateFlow<ShareUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { _uiState.value = ShareUiState.Done(magnetSender.send(text)) }
    }

    /** Builds a [ShareViewModel] for one share: [text] is what the sharing app handed over. */
    class Factory(
        private val magnetSender: MagnetSender,
        private val text: String?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ShareViewModel::class.java)) {
                "Unknown ViewModel class ${modelClass.name}"
            }
            return ShareViewModel(magnetSender, text) as T
        }
    }
}
