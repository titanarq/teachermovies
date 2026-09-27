package com.teachermovies.bridge.config

import kotlinx.serialization.Serializable

/**
 * The bridge's stored configuration (#271): the single `config.json` under
 * `~/.config/teachermovies-bridge/`, which [BridgeConfigStore] writes with mode 0600 because
 * [token] is a bearer token for the TV's API (ADR-0005 §4).
 *
 * A config holding both [tvUrl] and [token] is a paired bridge ([isPaired]); a fresh install has
 * neither. [deviceName] is the informational name sent at pairing, kept so a later `pair` reuses it
 * and `doctor` can say which laptop the TV paired with. Unknown keys are ignored on load, so a
 * config written by a newer bridge stays readable by this one.
 *
 * [toString] redacts [token]: a config reaching a log line, an exception message or a `doctor`
 * report never takes the token with it (AGENTS.md, "never log tokens or PINs").
 */
@Serializable
data class BridgeConfig(
    val tvUrl: String? = null,
    val token: String? = null,
    val deviceName: String? = null,
) {
    /** True once `pair` has stored both the TV's URL and the token that TV issued. */
    val isPaired: Boolean
        get() = tvUrl != null && token != null

    override fun toString(): String = "BridgeConfig(tvUrl=$tvUrl, token=<redacted>, deviceName=$deviceName)"
}
