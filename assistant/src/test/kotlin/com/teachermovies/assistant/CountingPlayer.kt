package com.teachermovies.assistant

import com.teachermovies.player.api.Player
import com.teachermovies.player.fake.FakePlayer

/** A [FakePlayer] seen through the calls the controller under test makes on it. */
class CountingPlayer(
    private val fake: FakePlayer,
) : Player by fake {
    val seeks = mutableListOf<Long>()
    var pauses = 0
    var plays = 0

    override fun seekTo(ms: Long) {
        seeks += ms
        fake.seekTo(ms)
    }

    override fun pause() {
        pauses += 1
        fake.pause()
    }

    override fun play() {
        plays += 1
        fake.play()
    }
}
