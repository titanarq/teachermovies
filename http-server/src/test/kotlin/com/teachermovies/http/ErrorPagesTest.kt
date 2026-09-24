package com.teachermovies.http

import com.teachermovies.http.auth.InMemorySettingsRepository
import com.teachermovies.http.auth.PairingManager
import com.teachermovies.http.auth.lanClient
import com.teachermovies.torrent.fake.FakeTorrentEngine
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class ErrorPagesTest {
    private val deps =
        ServerDeps(
            engine = FakeTorrentEngine(),
            space = { null },
            appVersion = "test",
            clock = { 0L },
            pairing = PairingManager(InMemorySettingsRepository(), SecureRandom(), { 0L }),
            allowTestRemoteHeader = true,
        )

    @Test
    fun `unknown api route is a 404 JSON error`() =
        testApplication {
            application { module(deps) }
            val client = lanClient()

            val response = client.get("/api/nope")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.contentType()!!.match(ContentType.Application.Json))
            assertEquals("""{"error":"not_found","message":"No such endpoint"}""", response.bodyAsText())
        }

    @Test
    fun `exception is a 500 JSON error without stack trace`() =
        testApplication {
            application {
                module(deps)
                routing { get("/api/boom") { error("secret internal detail") } }
            }
            val client = lanClient()

            val response = client.get("/api/boom")
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertTrue(response.contentType()!!.match(ContentType.Application.Json))
            assertEquals("""{"error":"internal","message":"Internal server error"}""", body)
            assertFalse(body.contains("secret internal detail"))
            assertFalse(body.contains("Exception"))
        }
}
