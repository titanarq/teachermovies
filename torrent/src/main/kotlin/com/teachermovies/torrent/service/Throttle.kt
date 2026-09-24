package com.teachermovies.torrent.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform

/**
 * Emits the first value at once, then at most one value per [periodMillis]: the latest one seen in
 * the meantime (values in between are dropped). The notification uses it so it is re-posted at
 * most once every 2 s however often the engine publishes.
 */
internal fun <T> Flow<T>.throttleLatest(periodMillis: Long): Flow<T> {
    require(periodMillis > 0) { "periodMillis must be positive, was $periodMillis" }
    return conflate().transform {
        emit(it)
        delay(periodMillis)
    }
}
