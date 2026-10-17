package com.teachermovies.http

import com.teachermovies.core.repo.fake.InMemoryTorrentRepository
import com.teachermovies.http.auth.InMemorySettingsRepository
import com.teachermovies.http.auth.PairingManager
import com.teachermovies.http.auth.lanClient
import com.teachermovies.torrent.fake.FakeTorrentEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** The temporary `PIN_PAIRING_REQUIRED=false` switch: every protected route is open on the LAN. */
class PairingDisabledRoutesTest {
    private val engine = FakeTorrentEngine()

    private fun deps(pairingRequired: Boolean) =
        ServerDeps(
            engine = engine,
            remove = engine::remove,
            space = { null },
            appVersion = "1.2.3",
            clock = { 0L },
            pairing = PairingManager(InMemorySettingsRepository(), SecureRandom(), { 0L }),
            subtitles = FakeSubtitleStore(),
            library = InMemoryTorrentRepository(),
            allowTestRemoteHeader = true,
            pairingRequired = pairingRequired,
        )

    // One phone-only, one both-scopes and one bridge-only route.
    private val protectedPaths = listOf("/api/torrents", "/api/library", "/api/logs", "/api/bridge/subtitle-needs")

    @Test
    fun `flag false - protected routes of every scope answer 200 without a token or with a bad one`() =
        testApplication {
            application { module(deps(pairingRequired = false)) }
            val client = lanClient()
            for (path in protectedPaths) {
                assertEquals(path, HttpStatusCode.OK, client.get(path).status)
                val bad = client.get(path) { header(HttpHeaders.Authorization, "Bearer not-a-token") }
                assertEquals(path, HttpStatusCode.OK, bad.status)
                val malformed = client.get(path) { header(HttpHeaders.Authorization, "garbage") }
                assertEquals(path, HttpStatusCode.OK, malformed.status)
            }
        }

    @Test
    fun `flag true - the same routes stay 401 without a token`() =
        testApplication {
            application { module(deps(pairingRequired = true)) }
            val client = lanClient()
            for (path in protectedPaths) {
                assertEquals(path, HttpStatusCode.Unauthorized, client.get(path).status)
            }
        }

    @Test
    fun `status exposes pairingRequired false`() =
        testApplication {
            application { module(deps(pairingRequired = false)) }
            val client = lanClient()
            assertTrue(client.get("/api/status").bodyAsText().contains("\"pairingRequired\":false"))
        }

    @Test
    fun `status exposes pairingRequired true`() =
        testApplication {
            application { module(deps(pairingRequired = true)) }
            val client = lanClient()
            assertTrue(client.get("/api/status").bodyAsText().contains("\"pairingRequired\":true"))
        }
}
