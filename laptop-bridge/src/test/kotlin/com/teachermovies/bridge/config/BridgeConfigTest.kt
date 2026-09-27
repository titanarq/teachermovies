package com.teachermovies.bridge.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored configuration itself (#271): what counts as a paired bridge, and the rule that must
 * never slip -- a [BridgeConfig] reaching a log line, an exception or a `doctor` report carries no
 * token with it.
 */
class BridgeConfigTest {
    private val config =
        BridgeConfig(
            tvUrl = "http://192.168.1.20:8787",
            token = "tok_super-secreto_9f3a",
            deviceName = "salon",
        )

    @Test
    fun `toString redacts the token and keeps what a human may need`() {
        val text = config.toString()

        assertFalse(text.contains("tok_super-secreto_9f3a"))
        assertTrue(text.contains("<redacted>"))
        assertTrue(text.contains("http://192.168.1.20:8787"))
        assertTrue(text.contains("salon"))
    }

    @Test
    fun `paired means a URL and a token, and nothing less`() {
        assertFalse(BridgeConfig().isPaired)
        assertFalse(BridgeConfig(tvUrl = "http://192.168.1.20:8787").isPaired)
        assertFalse(BridgeConfig(token = "tok_super-secreto_9f3a").isPaired)
        assertTrue(config.isPaired)
    }
}
