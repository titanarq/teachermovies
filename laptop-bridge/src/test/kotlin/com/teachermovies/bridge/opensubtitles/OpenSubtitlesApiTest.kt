package com.teachermovies.bridge.opensubtitles

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Instant

/**
 * [OpenSubtitlesApi] against [FakeOpenSubtitles] (#281): the headers every request carries, the JWT
 * login and the re-login on a 401, the search parameters, the download and its quota bookkeeping,
 * and the mapping of refusals onto [OsFailure] -- never a credential in a failure or a log line.
 */
class OpenSubtitlesApiTest {
    private val fake = FakeOpenSubtitles()
    private val httpClient = HttpClient(CIO)
    private var clock = Instant.parse("2026-09-28T12:00:00Z")

    private val credentials = OpenSubtitlesCredentials(fake.apiKey, fake.username, fake.password)

    private fun api(
        creds: OpenSubtitlesCredentials = credentials,
        baseUrl: String = fake.apiUrl,
    ) = OpenSubtitlesApi(httpClient, creds, baseUrl = baseUrl, now = { clock })

    @After
    fun tearDown() {
        httpClient.close()
        fake.close()
    }

    private fun apiCalls() = fake.received.filter { it.path.startsWith("/api/v1") }

    @Test
    fun `every API request carries the Api-Key and a User-Agent`() =
        runBlocking {
            val api = api()
            fake.subtitles["hash:0123456789abcdef"] = listOf(FakeOpenSubtitles.Sub(1, "en"))
            fake.files[1] = "1\n".toByteArray()

            api.search(SearchQuery(listOf("en"), moviehash = "0123456789abcdef"))
            api.download(1)

            val calls = apiCalls()
            assertEquals(listOf("/api/v1/subtitles", "/api/v1/login", "/api/v1/download"), calls.map { it.path })
            calls.forEach {
                assertEquals(fake.apiKey, it.apiKey)
                assertEquals(OpenSubtitlesApi.DEFAULT_USER_AGENT, it.userAgent)
            }
        }

    @Test
    fun `the credentials file may name its own User-Agent`() =
        runBlocking {
            val api = api(OpenSubtitlesCredentials(fake.apiKey, fake.username, fake.password, userAgent = "casa v2.1"))

            api.search(SearchQuery(listOf("en"), title = "matrix"))

            assertEquals("casa v2.1", fake.received.single().userAgent)
        }

    @Test
    fun `a download logs in first and sends the JWT, and a search does not log in for it`() =
        runBlocking {
            val api = api()
            fake.files[7] = "x".toByteArray()

            api.search(SearchQuery(listOf("en"), title = "matrix"))
            assertEquals(0, fake.loginCount)
            assertNull(fake.received.single().authorization)

            assertTrue(api.download(7) is DownloadOutcome.Link)
            assertEquals(1, fake.loginCount)
            val login = fake.received.first { it.path == "/api/v1/login" }
            assertTrue(login.body.contains("\"username\":\"${fake.username}\""))
            assertEquals("Bearer jwt-1", fake.received.last { it.path == "/api/v1/download" }.authorization)
        }

    @Test
    fun `the JWT is reused, and a 401 logs in again and repeats the request once`() =
        runBlocking {
            val api = api()
            fake.files[1] = "a".toByteArray()

            assertTrue(api.download(1) is DownloadOutcome.Link)
            assertTrue(api.download(1) is DownloadOutcome.Link)
            assertEquals(1, fake.loginCount)

            fake.expireJwt()
            assertTrue(api.download(1) is DownloadOutcome.Link)

            assertEquals(2, fake.loginCount)
            val downloads = fake.received.filter { it.path == "/api/v1/download" }
            assertEquals(
                listOf("Bearer jwt-1", "Bearer jwt-1", "Bearer jwt-1", "Bearer jwt-2"),
                downloads.map { it.authorization },
            )
        }

    @Test
    fun `a wrong password is LoginRefused and a wrong API key Unauthorized, neither printing them`() =
        runBlocking {
            val wrongPassword = api(OpenSubtitlesCredentials(fake.apiKey, fake.username, "otra-contraseña"))
            val refused = wrongPassword.download(1)
            assertEquals(DownloadOutcome.Failed(OsFailure.LoginRefused), refused)

            val wrongKey = api(OpenSubtitlesCredentials("clave-mala", fake.username, fake.password))
            val search = wrongKey.search(SearchQuery(listOf("en"), title = "matrix"))
            assertEquals(OsResult.Failure(OsFailure.Unauthorized), search)
            assertFalse(refused.toString().contains("otra-contraseña"))
            assertFalse(search.toString().contains("clave-mala"))
        }

    @Test
    fun `credentials render redacted`() {
        val text = credentials.toString()

        assertFalse(text.contains(fake.apiKey))
        assertFalse(text.contains(fake.username))
        assertFalse(text.contains(fake.password))
    }

    @Test
    fun `search sends only the fields it has, sorted and lower-cased, with the IMDb id as digits`() =
        runBlocking {
            val api = api()

            api.search(SearchQuery(listOf("es", "ea"), moviehash = "ABCDEF0123456789"))
            api.search(SearchQuery(listOf("en"), imdbId = "tt0133093"))
            api.search(SearchQuery(listOf("en"), title = "The Matrix", year = 1999))

            val queries = fake.received.map { it.query }
            assertEquals("languages=ea%2Ces&moviehash=abcdef0123456789", queries[0])
            assertEquals("imdb_id=133093&languages=en", queries[1])
            assertEquals("languages=en&query=the+matrix&year=1999", queries[2].replace("%20", "+"))
        }

    @Test
    fun `search maps the attributes ranking needs`() =
        runBlocking {
            fake.subtitles["hash:0123456789abcdef"] =
                listOf(
                    FakeOpenSubtitles.Sub(
                        fileId = 42,
                        language = "ea",
                        hearingImpaired = true,
                        machineTranslated = true,
                        aiTranslated = true,
                        foreignPartsOnly = true,
                        hashMatch = true,
                        fromTrusted = true,
                        downloadCount = 900,
                    ),
                )

            val mapped =
                (
                    api().search(
                        SearchQuery(listOf("ea"), moviehash = "0123456789abcdef"),
                    ) as OsResult.Success
                ).value.single()

            assertEquals(
                SubtitleCandidate(
                    fileId = 42,
                    fileName = "file-42",
                    languageCode = "ea",
                    hearingImpaired = true,
                    machineTranslated = true,
                    aiTranslated = true,
                    foreignPartsOnly = true,
                    hashMatch = true,
                    fromTrusted = true,
                    downloadCount = 900,
                    release = "release-42",
                ),
                mapped,
            )
        }

    @Test
    fun `a download tracks the remaining quota and its reset`() =
        runBlocking {
            val api = api()
            fake.remaining = 5
            fake.allowed = 20
            fake.files[3] = "c".toByteArray()

            val link = api.download(3) as DownloadOutcome.Link

            assertEquals("${fake.baseUrl}/files/3", link.link)
            assertEquals("sub-3.srt", link.fileName)
            assertEquals(
                Quota(remaining = 4, allowed = 20, resetsAt = Instant.parse(fake.resetTimeUtc)),
                api.quota.current,
            )
            assertTrue(
                fake.received
                    .last { it.path == "/api/v1/download" }
                    .body
                    .contains("\"sub_format\":\"srt\""),
            )
        }

    @Test
    fun `a 406 is QuotaExhausted, and later downloads are refused locally until the reset`() =
        runBlocking {
            val api = api()
            fake.remaining = 0

            val first = api.download(3)

            val expected = Quota(remaining = 0, allowed = 20, resetsAt = Instant.parse(fake.resetTimeUtc))
            assertEquals(DownloadOutcome.QuotaExhausted(expected), first)
            val downloadsSoFar = fake.received.count { it.path == "/api/v1/download" }

            assertEquals(DownloadOutcome.QuotaExhausted(expected), api.download(3))
            assertEquals(downloadsSoFar, fake.received.count { it.path == "/api/v1/download" })

            clock = Instant.parse(fake.resetTimeUtc).plusSeconds(1)
            fake.remaining = 20
            fake.files[3] = "c".toByteArray()
            assertTrue(api.download(3) is DownloadOutcome.Link)
        }

    @Test
    fun `refreshQuota asks OpenSubtitles for the remaining downloads`() =
        runBlocking {
            val api = api()
            fake.remaining = 13
            fake.allowed = 20

            assertEquals(OsResult.Success(Quota(remaining = 13, allowed = 20)), api.refreshQuota())
        }

    @Test
    fun `fetch returns the raw bytes and sends no credential to the link`() =
        runBlocking {
            val api = api()
            val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'h'.code.toByte())
            fake.files[9] = bytes
            val link = api.download(9) as DownloadOutcome.Link

            val fetched = api.fetch(link.link)

            assertArrayEquals(bytes, (fetched as OsResult.Success).value)
            val fileRequest = fake.received.last { it.path == "/files/9" }
            assertNull(fileRequest.apiKey)
            assertNull(fileRequest.authorization)
        }

    @Test
    fun `refusals and an unreachable server map onto OsFailure`() =
        runBlocking {
            fake.failSearchWith = HttpStatusCode.TooManyRequests
            assertEquals(OsResult.Failure(OsFailure.RateLimited), api().search(SearchQuery(listOf("en"), title = "x")))

            fake.failSearchWith = HttpStatusCode.ServiceUnavailable
            assertEquals(OsResult.Failure(OsFailure.Http(503)), api().search(SearchQuery(listOf("en"), title = "x")))

            val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
            val down = api(baseUrl = "http://127.0.0.1:$closed/api/v1").search(SearchQuery(listOf("en"), title = "x"))
            assertTrue((down as OsResult.Failure).failure is OsFailure.Network)
        }

    @Test
    fun `imdbDigits strips the prefix and leading zeros and refuses anything else`() {
        assertEquals("133093", OpenSubtitlesApi.imdbDigits("tt0133093"))
        assertEquals("133093", OpenSubtitlesApi.imdbDigits("133093"))
        assertNull(OpenSubtitlesApi.imdbDigits("tt"))
        assertNull(OpenSubtitlesApi.imdbDigits("matrix"))
    }
}
