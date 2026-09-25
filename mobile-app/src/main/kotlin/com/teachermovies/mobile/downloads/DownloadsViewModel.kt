package com.teachermovies.mobile.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.teachermovies.mobile.api.ApiFailure
import com.teachermovies.mobile.api.ApiResult
import com.teachermovies.mobile.api.TorrentSummary
import com.teachermovies.mobile.api.TvApi
import com.teachermovies.mobile.data.PairedTv
import com.teachermovies.mobile.data.PairedTvStore
import com.teachermovies.mobile.send.MagnetSender
import com.teachermovies.mobile.send.SendOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The paired TV's download list (#198), polled with [TvApi.torrents] while the screen is subscribed
 * and a TV is stored -- once on subscribing and then every [pollIntervalMs] -- so a screen nobody
 * looks at costs the TV no request. A poll that fails keeps the list it last got and marks the TV
 * offline; a token the TV refuses is forgotten, which is what returns the app to pairing through
 * `ConnectionViewModel`. [send] posts a magnet with [MagnetSender] and shows its outcome once as
 * [DownloadsUiState.notice]. Nothing here logs the token.
 */
class DownloadsViewModel(
    private val api: TvApi,
    private val store: PairedTvStore,
    private val magnetSender: MagnetSender,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) : ViewModel() {
    private val _uiState = MutableStateFlow<DownloadsUiState>(DownloadsUiState.Loading())

    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    // One buffered unit is all a "poll again now" needs: a second one before that poll asks for the
    // same thing. A refresh nobody is waiting for is dropped, which is also what it should do.
    private val refreshes =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    init {
        viewModelScope.launch {
            polls().collect { polled -> _uiState.update { shown -> polled.withNotice(shown.notice) } }
        }
    }

    /** Sends the magnet inside [text] to the TV and shows what came of it as a transient notice. */
    fun send(text: String) {
        viewModelScope.launch {
            val outcome = magnetSender.send(text)
            _uiState.update { it.withNotice(outcome.message) }
            // A torrent the TV accepted has to appear at once, not at the next interval.
            if (outcome is SendOutcome.Sent) refreshes.tryEmit(Unit)
        }
    }

    /** The notice has been shown: drop it, so it is not shown again. */
    fun noticeShown() {
        _uiState.update { it.withNotice(null) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun polls(): Flow<DownloadsUiState.Loaded> =
        subscriptionState().flatMapLatest { subscribed ->
            if (!subscribed) {
                nothing()
            } else {
                store.pairedTv.flatMapLatest { tv -> if (tv == null) nothing() else pollEvery(tv) }
            }
        }

    /** Whether a screen is collecting [uiState] right now. */
    private fun subscriptionState(): Flow<Boolean> =
        _uiState.subscriptionCount
            .map { subscribers -> subscribers > 0 }
            .distinctUntilChanged()

    private fun pollEvery(tv: PairedTv): Flow<DownloadsUiState.Loaded> =
        flow {
            var items = emptyList<TorrentSummary>()
            var offline = false
            while (true) {
                when (val result = api.torrents(tv.baseUrl, tv.token)) {
                    is ApiResult.Success -> {
                        items = result.value
                        offline = false
                    }

                    is ApiResult.Failure -> {
                        // A token the TV refuses is forgotten, which is what takes the app back to
                        // pairing; any other failure only marks the TV offline and keeps the list.
                        if (result.failure == ApiFailure.Unauthorized) store.clear()
                        offline = true
                    }
                }
                emit(DownloadsUiState.Loaded(items, offline))
                withTimeoutOrNull(pollIntervalMs) { refreshes.first() }
            }
        }

    // "Nothing to poll". It never completes either: a completed flow would end the collector for
    // good, and the list would never start polling again.
    private fun nothing(): Flow<DownloadsUiState.Loaded> = flow { awaitCancellation() }

    private fun DownloadsUiState.withNotice(notice: String?): DownloadsUiState =
        when (this) {
            is DownloadsUiState.Loading -> copy(notice = notice)
            is DownloadsUiState.Loaded -> copy(notice = notice)
        }

    /** Builds a [DownloadsViewModel] from the container's parts. */
    class Factory(
        private val api: TvApi,
        private val store: PairedTvStore,
        private val magnetSender: MagnetSender,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DownloadsViewModel::class.java)) {
                "Unknown ViewModel class ${modelClass.name}"
            }
            return DownloadsViewModel(api, store, magnetSender) as T
        }
    }

    companion object {
        /** How often the TV is polled while the downloads screen is shown: what its web UI uses. */
        const val DEFAULT_POLL_INTERVAL_MS = 3_000L
    }
}
