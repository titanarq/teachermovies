package com.teachermovies.bridge.run

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Reconnect delays of the run loop (#277): [initial], then doubling up to [max], back to [initial]
 * once a stream opens again. No jitter: one bridge talks to one TV, so there is no crowd of
 * clients to spread out.
 */
class Backoff(
    private val initial: Duration = 1.seconds,
    private val max: Duration = 1.minutes,
) {
    init {
        require(initial.isPositive() && max >= initial) { "backoff needs 0 < initial <= max" }
    }

    private var current = initial

    /** The delay to wait now; the following call returns twice as much, up to [max]. */
    fun next(): Duration {
        val delay = current
        current = (current * 2).coerceAtMost(max)
        return delay
    }

    fun reset() {
        current = initial
    }
}
