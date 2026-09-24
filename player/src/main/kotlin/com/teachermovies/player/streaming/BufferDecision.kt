package com.teachermovies.player.streaming

/** Whether playback of a still-downloading file may go on or has to wait for the buffer. */
sealed interface BufferDecision {
    data object Play : BufferDecision

    data object Wait : BufferDecision
}

/**
 * The next [BufferDecision] given the [current] one and the [readyBytes] ready ahead of the
 * playback position, with hysteresis: from [BufferDecision.Play] it only waits below
 * [StreamPolicy.underrunBytes]; from [BufferDecision.Wait] it only plays again at
 * [StreamPolicy.resumeBytes] or more. Always [BufferDecision.Play] when [atEndOfFile], because
 * the last seconds of a file can never reach the resume threshold.
 */
fun StreamPolicy.decide(
    current: BufferDecision,
    readyBytes: Long,
    atEndOfFile: Boolean,
): BufferDecision {
    if (atEndOfFile) return BufferDecision.Play
    return when (current) {
        BufferDecision.Play -> if (readyBytes < underrunBytes) BufferDecision.Wait else BufferDecision.Play
        BufferDecision.Wait -> if (readyBytes >= resumeBytes) BufferDecision.Play else BufferDecision.Wait
    }
}
