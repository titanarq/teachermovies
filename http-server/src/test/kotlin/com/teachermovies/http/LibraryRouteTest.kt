package com.teachermovies.http

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.Torrent
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.fake.InMemoryTorrentRepository
import com.teachermovies.http.auth.InMemorySettingsRepository
import com.teachermovies.http.auth.PairingManager
import com.teachermovies.http.auth.lanClient
import com.teachermovies.torrent.fake.FakeTorrentEngine
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** `GET /api/library` (#73): empty and populated lists, newest first, no paths, 401 without a token. */
class LibraryRouteTest {
    private val repo = InMemoryTorrentRepository()
    private val pairing = PairingManager(InMemorySettingsRepository(), SecureRandom(), { 0L })

    private val engine = FakeTorrentEngine()

    private val deps =
        ServerDeps(
            engine = engine,
            remove = engine::remove,
            space = { null },
            appVersion = "test",
            clock = { 0L },
            pairing = pairing,
            subtitles = FakeSubtitleStore(),
            library = repo,
            allowTestRemoteHeader = true,
        )

    private fun ApplicationTestBuilder.setUp(): HttpClient {
        application { module(deps) }
        return lanClient()
    }

    private suspend fun HttpClient.pairedToken(): String {
        val body =
            post("/api/pair") {
                contentType(ContentType.Application.Json)
                setBody("""{"pin":"${pairing.currentPin()}"}""")
            }.bodyAsText()
        return Json
            .parseToJsonElement(body)
            .jsonObject["token"]!!
            .jsonPrimitive.content
    }

    private fun torrent(
        hashChar: Char,
        name: String,
        state: DownloadState,
        totalBytes: Long = 1_000L,
    ): Torrent =
        Torrent(
            id = TorrentId(hashChar.toString().repeat(40)),
            name = name,
            state = state,
            progressPercent = if (state == DownloadState.Completed) 100.0 else 10.0,
            downloadedBytes = totalBytes,
            totalBytes = totalBytes,
            savePath = "/storage/emulated/0/Movies/$hashChar",
            mainFileIndex = 0,
            errorMessage = null,
        )

    @Test
    fun `empty library is an empty list`() =
        testApplication {
            val client = setUp()
            val token = client.pairedToken()

            val response = client.get("/api/library") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.contentType()!!.match(ContentType.Application.Json))
            assertEquals("[]", response.bodyAsText())
        }

    @Test
    fun `lists completed movies newest first without any path`() =
        testApplication {
            // 2026-09-24T10:00:00Z and 2026-09-25T08:30:15.250Z.
            val older = 1_790_244_000_000L
            val newer = 1_790_325_015_250L
            repo.upsert(torrent('a', "Old Movie", DownloadState.Completed, 700L), "/vol/Movies/a/Old.mkv", older)
            repo.upsert(torrent('b', "New Movie", DownloadState.Completed, 900L), "/vol/Movies/b/New.mkv", newer)
            repo.updatePlayback(
                TorrentId("a".repeat(40)),
                positionMs = 61_000L,
                audioTrackId = null,
                subtitleTrackId = null,
            )
            // Not yet in the library: still downloading.
            repo.upsert(torrent('c', "Downloading", DownloadState.Downloading), "/vol/Movies/c/D.mkv", newer + 1)

            val client = setUp()
            val token = client.pairedToken()
            val response = client.get("/api/library") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            val items = Json.parseToJsonElement(body).jsonArray
            assertEquals(2, items.size)

            val first = items[0].jsonObject
            assertEquals("b".repeat(40), first["id"]!!.jsonPrimitive.content)
            assertEquals("New Movie", first["title"]!!.jsonPrimitive.content)
            assertEquals(900L, first["sizeBytes"]!!.jsonPrimitive.long)
            assertEquals("2026-09-25T08:30:15.250Z", first["completedAt"]!!.jsonPrimitive.content)
            assertEquals(0L, first["lastPositionMs"]!!.jsonPrimitive.long)

            val second = items[1].jsonObject
            assertEquals("a".repeat(40), second["id"]!!.jsonPrimitive.content)
            assertEquals("2026-09-24T10:00:00Z", second["completedAt"]!!.jsonPrimitive.content)
            assertEquals(61_000L, second["lastPositionMs"]!!.jsonPrimitive.long)

            items.forEach { item ->
                assertEquals(setOf("id", "title", "sizeBytes", "completedAt", "lastPositionMs"), item.jsonObject.keys)
                assertFalse(item.jsonObject.containsKey("path"))
            }
            assertFalse("no file system path leaks", body.contains("/vol/") || body.contains("/storage/"))
        }

    @Test
    fun `401 without a token`() =
        testApplication {
            val client = setUp()

            val missing = client.get("/api/library")
            assertEquals(HttpStatusCode.Unauthorized, missing.status)
            assertEquals("Bearer", missing.headers[HttpHeaders.WWWAuthenticate])

            val wrong = client.get("/api/library") { bearerAuth("not-a-real-token") }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        }
}
