package com.teachermovies.mobile.data

import kotlinx.coroutines.flow.Flow

/** Persists the one paired TV and its token (#196). */
interface PairedTvStore {
    /** The paired TV, or `null` when the phone is not paired. */
    val pairedTv: Flow<PairedTv?>

    /** Stores [tv], replacing any previously paired TV. */
    suspend fun save(tv: PairedTv)

    /** Replaces the stored TV's base URL (the TV moved to another address); no-op when none is stored. */
    suspend fun updateBaseUrl(baseUrl: String)

    /** Forgets the paired TV and its token. */
    suspend fun clear()
}
