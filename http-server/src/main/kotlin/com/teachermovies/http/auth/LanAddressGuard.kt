package com.teachermovies.http.auth

import com.teachermovies.http.ApiError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.response.respond

/**
 * Header a test-only flag may honour in place of the socket's real remote address, so a route
 * test can exercise a refused public address without opening a real non-LAN connection.
 */
const val TEST_REMOTE_HEADER = "X-Test-Remote"

/**
 * Refuses every request whose remote address is not on the LAN (#59): 403
 * `{"error":"not_lan"}`, and no route handler ever runs for it.
 *
 * The remote address is `call.request.origin.remoteHost`, the actual socket peer, so a port
 * forward or a spoofed header cannot fake it. The only exception is [allowTestRemoteHeader]: when
 * a test opts in via that (production-false) [com.teachermovies.http.ServerDeps] flag,
 * [TEST_REMOTE_HEADER] overrides the remote address for that call.
 */
fun Application.installLanAddressGuard(allowTestRemoteHeader: Boolean) {
    install(
        createApplicationPlugin("LanAddressGuard") {
            onCall { call ->
                val remote =
                    (if (allowTestRemoteHeader) call.request.header(TEST_REMOTE_HEADER) else null)
                        ?: call.request.origin.remoteHost
                if (!LanAddressPolicy.isAllowed(remote)) {
                    call.respond(HttpStatusCode.Forbidden, ApiError("not_lan", "Not a LAN address"))
                }
            }
        },
    )
}
