package com.teachermovies.tv.ui.server

import com.teachermovies.http.ServerState
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerStatusLabelTest {

    @Test
    fun runningNeedsNoStatusLine() {
        assertEquals(ServerStatusLabel.Running, ServerStatusLabel.of(ServerState.Running(8787)))
    }

    @Test
    fun stoppedIsServidorDetenido() {
        assertEquals(ServerStatusLabel.Stopped, ServerStatusLabel.of(ServerState.Stopped))
    }

    @Test
    fun bindFailureIsPortInUse() {
        assertEquals(ServerStatusLabel.PortInUse, ServerStatusLabel.of(ServerState.Failed(8787, "Address already in use")))
        assertEquals(ServerStatusLabel.PortInUse, ServerStatusLabel.of(ServerState.Failed(8787, "bind failed: EADDRINUSE (Address already in use)")))
    }

    @Test
    fun otherFailureKeepsItsReason() {
        assertEquals(ServerStatusLabel.Failed("Permission denied"), ServerStatusLabel.of(ServerState.Failed(80, "Permission denied")))
    }
}
