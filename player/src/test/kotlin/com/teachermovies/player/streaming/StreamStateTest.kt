package com.teachermovies.player.streaming

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamStateTest {
    private fun describe(state: StreamState): String =
        when (state) {
            StreamState.Idle -> "idle"
            is StreamState.Preparing -> "preparing ${state.readyBytes}/${state.requiredBytes}"
            StreamState.Streaming -> "streaming"
            is StreamState.Buffering -> "buffering ${state.readyBytes}/${state.resumeAtBytes}"
            is StreamState.Failed -> "failed ${state.reason}"
        }

    private fun describe(result: StreamResult): String =
        when (result) {
            StreamResult.Opened -> "opened"
            StreamResult.UnknownTorrent -> "unknown"
            StreamResult.Unsupported -> "unsupported"
            is StreamResult.Failed -> "failed ${result.reason}"
        }

    @Test
    fun everyStreamStateMemberMatches() {
        assertEquals("idle", describe(StreamState.Idle))
        assertEquals("preparing 1/2", describe(StreamState.Preparing(readyBytes = 1, requiredBytes = 2)))
        assertEquals("streaming", describe(StreamState.Streaming))
        assertEquals("buffering 3/4", describe(StreamState.Buffering(readyBytes = 3, resumeAtBytes = 4)))
        assertEquals("failed disk", describe(StreamState.Failed("disk")))
    }

    @Test
    fun everyStreamResultMemberMatches() {
        assertEquals("opened", describe(StreamResult.Opened))
        assertEquals("unknown", describe(StreamResult.UnknownTorrent))
        assertEquals("unsupported", describe(StreamResult.Unsupported))
        assertEquals("failed disk", describe(StreamResult.Failed("disk")))
    }
}
