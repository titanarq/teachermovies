package com.teachermovies.http.auth

import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.server.testing.ApplicationTestBuilder

/**
 * A test client that claims (via [TEST_REMOTE_HEADER]) to be calling from a LAN address.
 *
 * `testApplication`'s own `client` never opens a real socket, so `call.request.origin.remoteHost`
 * defaults to the fixed placeholder `"localhost"` -- not an IP literal, so [LanAddressPolicy]
 * correctly refuses it (#59). Route tests that aren't themselves testing the LAN-address guard use
 * this client (with a `ServerDeps.allowTestRemoteHeader = true`) instead of the plain one.
 */
fun ApplicationTestBuilder.lanClient(): HttpClient =
    createClient {
        defaultRequest { header(TEST_REMOTE_HEADER, "127.0.0.1") }
    }
