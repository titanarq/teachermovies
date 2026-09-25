package com.teachermovies.mobile.connection

import com.teachermovies.discovery.client.DiscoveredTv
import com.teachermovies.mobile.data.PairedTv

/** What the connection screens show (#197); produced only by [ConnectionViewModel]. */
sealed interface ConnectionUiState {
    /** The paired-TV store has not answered yet. */
    data object Loading : ConnectionUiState

    /**
     * Not paired: the TVs discovered so far ([tvs], sorted by instance name) and, after a typed
     * address that could not be parsed, [addressError].
     */
    data class Searching(
        val tvs: List<DiscoveredTv>,
        val addressError: String? = null,
    ) : ConnectionUiState

    /**
     * Asking for the PIN of the TV at [baseUrl]. [busy] while `POST /api/pair` is in flight;
     * [error] is the last rejection, in Spanish.
     */
    data class Pairing(
        val instanceName: String,
        val baseUrl: String,
        val error: String? = null,
        val busy: Boolean = false,
    ) : ConnectionUiState

    /** Paired with [pairedTv] (its `toString` redacts the token). */
    data class Connected(
        val pairedTv: PairedTv,
    ) : ConnectionUiState
}
