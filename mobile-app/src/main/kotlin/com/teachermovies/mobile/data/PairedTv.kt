package com.teachermovies.mobile.data

/**
 * The TV this phone is paired with (#196): its NSD [instanceName], the [baseUrl] it was last
 * reached at (for example `http://192.168.1.20:8787`) and the bearer [token] `POST /api/pair`
 * returned. [toString] redacts the token so it never reaches a log.
 */
data class PairedTv(
    val instanceName: String,
    val baseUrl: String,
    val token: String,
) {
    override fun toString(): String = "PairedTv(instanceName=$instanceName, baseUrl=$baseUrl, token=<redacted>)"
}
