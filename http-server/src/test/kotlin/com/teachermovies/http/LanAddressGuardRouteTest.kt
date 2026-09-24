package com.teachermovies.http

import com.teachermovies.core.repo.fake.InMemoryTorrentRepository
import com.teachermovies.http.auth.InMemorySettingsRepository
import com.teachermovies.http.auth.PairingManager
import com.teachermovies.http.auth.TEST_REMOTE_HEADER
import com.teachermovies.torrent.fake.FakeTorrentEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** #59: the application-level LAN-address guard, exercised through the real routing. */
class LanAddressGuardRouteTest {
    private fun deps(allowTestRemoteHeader: Boolean) =
        ServerDeps(
            engine = FakeTorrentEngine(),
            space = { null },
            appVersion = "test",
            clock = { 0L },
            pairing = PairingManager(InMemorySettingsRepository(), SecureRandom(), { 0L }),
            subtitles = FakeSubtitleStore(),
            library = InMemoryTorrentRepository(),
            allowTestRemoteHeader = allowTestRemoteHeader,
        )

    @Test
    fun `a public remote address is refused with 403 not_lan`() =
        testApplication {
            application { module(deps(allowTestRemoteHeader = true)) }

            val response = client.get("/api/status") { header(TEST_REMOTE_HEADER, "8.8.8.8") }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.contentType()!!.match(ContentType.Application.Json))
            assertEquals("""{"error":"not_lan","message":"Not a LAN address"}""", response.bodyAsText())
        }

    @Test
    fun `a LAN remote address is let through`() =
        testApplication {
            application { module(deps(allowTestRemoteHeader = true)) }

            val response = client.get("/api/status") { header(TEST_REMOTE_HEADER, "192.168.1.50") }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `the test header is ignored unless the deps flag is set`() =
        testApplication {
            // allowTestRemoteHeader = false: the header (even a LAN address) must be ignored, so
            // the guard falls back to the real remote address -- the test client's fixed
            // placeholder "localhost", which isn't an IP literal and so is refused too.
            application { module(deps(allowTestRemoteHeader = false)) }

            val response = client.get("/api/status") { header(TEST_REMOTE_HEADER, "192.168.1.50") }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals("""{"error":"not_lan","message":"Not a LAN address"}""", response.bodyAsText())
        }
}
