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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class WebUiRouteTest {
    private val engine = FakeTorrentEngine()

    private fun deps() =
        ServerDeps(
            engine = engine,
            remove = engine::remove,
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
    fun `app js sends the bearer header and opens SSE with the token query`() =
        testApplication {
            application { module(deps()) }
            val body = lanClient().get("/static/app.js").bodyAsText()

            assertTrue(body.contains("'Bearer '"))
            assertTrue(body.contains("new EventSource('/api/events?token=' + encodeURIComponent("))
            assertTrue(body.contains("POLL_EVERY_MS = 3000"))
        }

    @Test
    fun `app js uploads subtitles and deletes downloads through the api`() =
        testApplication {
            application { module(deps()) }
            val body = lanClient().get("/static/app.js").bodyAsText()

            assertTrue(body.contains("api('/api/subtitles', { method: 'POST', body: form })"))
            assertTrue(body.contains("'?deleteFiles='"))
            assertTrue(body.contains("{ method: 'DELETE' }"))
            assertTrue(body.contains("SUBIR SUBTÍTULO"))
            assertTrue(body.contains("'.srt,.ass,.ssa,.vtt'"))
        }

    @Test
    fun `app js offers buscar subtitulos on finished movies and follows the search`() =
        testApplication {
            application { module(deps()) }
            val body = lanClient().get("/static/app.js").bodyAsText()

            assertTrue(body.contains("BUSCAR SUBTÍTULOS"))
            assertTrue(body.contains("row.searchButton.hidden = t.state !== 'completed'"))
            assertTrue(body.contains("'/api/library/' + encodeURIComponent(id) + '/subtitles'"))
            assertTrue(body.contains("api(path + '/search', { method: 'POST' })"))
            assertTrue(body.contains("Portátil no conectado"))
            assertTrue(body.contains("No se encontraron subtítulos"))
            assertTrue(body.contains("Subtítulos encontrados"))
        }

    @Test
    fun `index html has the delete dialog with the delete-files checkbox`() =
        testApplication {
            application { module(deps()) }
            val body = lanClient().get("/").bodyAsText()

            assertTrue(body.contains("id=\"del-dialog\""))
            assertTrue(body.contains("Borrar también los archivos"))
        }

    @Test
    fun `index html has the registros tab with level, follow and download controls`() =
        testApplication {
            application { module(deps()) }
            val body = lanClient().get("/").bodyAsText()

            assertTrue(body.contains("id=\"tab-logs\""))
            assertTrue(body.contains(">REGISTROS</button>"))
            assertTrue(body.contains("id=\"view-logs\""))
            for (level in listOf("debug", "info", "warn", "error")) {
                assertTrue("level $level", body.contains("<option value=\"$level\""))
            }
            assertTrue(body.contains("id=\"log-follow\""))
            assertTrue(body.contains("DESCARGAR .TXT"))
            assertTrue(body.contains("id=\"log-lines\""))
        }

    @Test
    fun `app js reads the log stream with fetch and the bearer header, never a token query`() =
        testApplication {
            application { module(deps()) }
            val body = lanClient().get("/static/app.js").bodyAsText()

            // Through api(): Authorization header, and a 401 clears the token and shows the PIN form.
            assertTrue(body.contains("api('/api/logs/stream?since=' + since + '&level='"))
            assertTrue(body.contains("res.body.getReader()"))
            assertTrue(body.contains("if (res.status === 401) {\n        onUnauthorized();"))
            assertTrue(body.contains("function onUnauthorized() {\n    clearToken();"))
            assertFalse(body.contains("/api/logs/stream?token="))
            assertFalse(Regex("""/api/logs[^']*token=""").containsMatchIn(body))
            // The only EventSource is /api/events'.
            assertEquals(1, Regex("new EventSource\\(").findAll(body).count())
        }

    @Test
    fun `app js polls the log page when the stream drops and keeps at most 2000 rendered lines`() =
        testApplication {
            application { module(deps()) }
            val body = lanClient().get("/static/app.js").bodyAsText()

            assertTrue(body.contains("LOG_MAX_DOM_LINES = 2000"))
            val domCap = "while (box.childNodes.length > LOG_MAX_DOM_LINES) box.removeChild(box.firstChild)"
            assertTrue(body.contains(domCap))
            assertTrue(body.contains("api('/api/logs?since=' + since + '&level='"))
            assertTrue(body.contains("LOG_POLL_EVERY_MS = 3000"))
            assertTrue(body.contains("startLogPolling();"))
            // Pause/follow and the .txt export of every kept line, not only the rendered ones.
            assertTrue(body.contains("button.textContent = logFollowing ? 'PAUSAR' : 'SEGUIR'"))
            assertTrue(body.contains("logEntries.map(function (entry) { return logText(entry, true); })"))
            assertTrue(body.contains("'.txt'"))
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
