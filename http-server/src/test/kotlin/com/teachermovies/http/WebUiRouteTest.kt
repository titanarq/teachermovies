package com.teachermovies.http

import com.teachermovies.core.repo.fake.InMemoryTorrentRepository
import com.teachermovies.http.auth.InMemorySettingsRepository
import com.teachermovies.http.auth.PairingManager
import com.teachermovies.http.auth.lanClient
import com.teachermovies.torrent.fake.FakeTorrentEngine
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class WebUiRouteTest {
    private fun deps() =
        ServerDeps(
            engine = FakeTorrentEngine(),
            space = { null },
            appVersion = "1.0",
            clock = { 0L },
            pairing = PairingManager(InMemorySettingsRepository(), SecureRandom(), { 0L }),
            subtitles = FakeSubtitleStore(),
            library = InMemoryTorrentRepository(),
            allowTestRemoteHeader = true,
        )

    @Test
    fun `root serves index html without a token and references both assets`() =
        testApplication {
            application { module(deps()) }
            val response = lanClient().get("/")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.contentType()!!.match(ContentType.Text.Html))
            val body = response.bodyAsText()
            assertTrue(body.contains("\"/static/app.js\""))
            assertTrue(body.contains("\"/static/app.css\""))
            assertTrue(body.contains("MOVIE ASSISTANT"))
        }

    @Test
    fun `app js is served as javascript`() =
        testApplication {
            application { module(deps()) }
            val response = lanClient().get("/static/app.js")

            assertEquals(HttpStatusCode.OK, response.status)
            val type = response.contentType()!!.withoutParameters()
            assertTrue(
                "unexpected $type",
                type.match(ContentType.Application.JavaScript) || type.match(ContentType.Text.JavaScript),
            )
        }

    @Test
    fun `app css is served as css`() =
        testApplication {
            application { module(deps()) }
            val response = lanClient().get("/static/app.css")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.contentType()!!.match(ContentType.Text.CSS))
        }

    @Test
    fun `unknown static file is 404 and unknown api route stays a JSON error`() =
        testApplication {
            application { module(deps()) }
            val client = lanClient()

            assertEquals(HttpStatusCode.NotFound, client.get("/static/nope.js").status)
            val api = client.get("/api/nope")
            assertEquals(HttpStatusCode.NotFound, api.status)
            assertEquals("""{"error":"not_found","message":"No such endpoint"}""", api.bodyAsText())
        }
}
