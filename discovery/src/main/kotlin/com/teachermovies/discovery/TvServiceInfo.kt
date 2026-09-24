package com.teachermovies.discovery

/**
 * What the TV announces on the LAN: the DNS-SD [instanceName], the HTTP [port], the DNS-SD
 * [serviceType] and one TXT record entry per [attributes] entry.
 */
data class TvServiceInfo(
    val instanceName: String,
    val port: Int,
    val serviceType: String = "_http._tcp",
    val attributes: Map<String, String> = emptyMap(),
)
