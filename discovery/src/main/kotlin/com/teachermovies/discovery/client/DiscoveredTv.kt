package com.teachermovies.discovery.client

/**
 * A TV service found and resolved on the LAN: its DNS-SD [instanceName], the [host] (an IP address
 * literal) and [port] to reach it, and its TXT record [attributes].
 */
data class DiscoveredTv(
    val instanceName: String,
    val host: String,
    val port: Int,
    val attributes: Map<String, String> = emptyMap(),
) {
    /** `http://<host>:<port>`, the base URL of the TV's HTTP API. */
    val baseUrl: String
        get() = "http://$host:$port"
}
