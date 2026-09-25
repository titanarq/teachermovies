package com.teachermovies.mobile.data.fake

import com.teachermovies.mobile.data.PairedTv
import com.teachermovies.mobile.data.PairedTvStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** [PairedTvStore] held in a [MutableStateFlow] (ADR-0003), optionally starting with [initial]. */
class InMemoryPairedTvStore(
    initial: PairedTv? = null,
) : PairedTvStore {
    private val state = MutableStateFlow(initial)

    override val pairedTv: StateFlow<PairedTv?> = state

    override suspend fun save(tv: PairedTv) {
        state.value = tv
    }

    override suspend fun updateBaseUrl(baseUrl: String) {
        state.update { it?.copy(baseUrl = baseUrl) }
    }

    override suspend fun clear() {
        state.value = null
    }
}
