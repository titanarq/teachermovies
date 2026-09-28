package com.teachermovies.bridge.opensubtitles

import com.teachermovies.bridge.opensubtitles.FakeOpenSubtitles.Sub
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * [SubtitleFinder] end to end over [FakeOpenSubtitles] (#281, ADR-0005 §5): the order of searches,
 * ranking, Castilian before Latin-American (labelled "latino") across every search, the quota it
 * reports, and the downloaded text arriving as UTF-8.
 */
class SubtitleFinderTest {
    private val fake = FakeOpenSubtitles()
    private val httpClient = HttpClient(CIO)
    private val api =
        OpenSubtitlesApi(
            httpClient,
            OpenSubtitlesCredentials(fake.apiKey, fake.username, fake.password),
            baseUrl = fake.apiUrl,
        )
    private val finder = SubtitleFinder(api)

    private val hash = "8e245d9679d31e12"
    private val movie =
        SubtitleRequest(
            SubtitleLanguage.SPANISH,
            moviehash = hash,
            imdbId = "tt0133093",
            title = "The Matrix",
            year = 1999,
        )

    @After
    fun tearDown() {
        httpClient.close()
        fake.close()
    }

    private fun serve(vararg ids: Long) =
        ids.forEach {
            fake.files[it] =
                "1\n00:00:01,000 --> 00:00:02,000\nhola $it\n".toByteArray()
        }

    private fun searches() = fake.received.filter { it.path == "/api/v1/subtitles" }.map { it.query }

    private fun downloads() = fake.received.filter { it.path == "/api/v1/download" }.map { it.body }

    @Test
    fun `a moviehash hit is downloaded without any fallback search`() =
        runBlocking {
            fake.subtitles["hash:$hash"] = listOf(Sub(11, "es"))
            serve(11)

            val found = finder.find(movie) as SubtitleSearch.Found

            assertEquals(11L, found.subtitle.fileId)
            assertEquals(SubtitleVariant.CASTILIAN, found.subtitle.variant)
            assertNull(found.subtitle.label)
            assertTrue(found.subtitle.hashMatch)
            assertEquals(1, searches().size)
            assertTrue(searches().single().contains("moviehash=$hash"))
            assertEquals(1, downloads().size)
        }

    @Test
    fun `no hash hit falls back to the IMDb id, then to title and year`() =
        runBlocking {
            fake.subtitles["query:the matrix"] = listOf(Sub(21, "es"))
            serve(21)

            val found = finder.find(movie) as SubtitleSearch.Found

            assertEquals(21L, found.subtitle.fileId)
            val queries = searches()
            assertEquals(3, queries.size)
            assertTrue(queries[0].contains("moviehash="))
            assertTrue(queries[1].contains("imdb_id=133093"))
            assertTrue(queries[2].contains("year=1999"))
        }

    @Test
    fun `searches the request cannot fill are skipped`() =
        runBlocking {
            fake.subtitles["query:the matrix"] = listOf(Sub(21, "en"))
            serve(21)

            finder.find(SubtitleRequest(SubtitleLanguage.ENGLISH, title = "The Matrix"))

            assertEquals(1, searches().size)
        }

    @Test
    fun `ranking prefers non-hearing-impaired and never takes a machine translation`() =
        runBlocking {
            fake.subtitles["hash:$hash"] =
                listOf(
                    Sub(1, "en", machineTranslated = true, downloadCount = 9999),
                    Sub(2, "en", aiTranslated = true, downloadCount = 9999),
                    Sub(3, "en", hearingImpaired = true, downloadCount = 500),
                    Sub(4, "en", downloadCount = 10),
                )
            serve(1, 2, 3, 4)

            val found = finder.find(SubtitleRequest(SubtitleLanguage.ENGLISH, moviehash = hash)) as SubtitleSearch.Found

            assertEquals(4L, found.subtitle.fileId)
            assertEquals(SubtitleVariant.ENGLISH, found.subtitle.variant)
            assertEquals(listOf("{\"file_id\":4,\"sub_format\":\"srt\"}"), downloads())
        }

    @Test
    fun `only machine translations is NotFound, and nothing is downloaded`() =
        runBlocking {
            fake.subtitles["hash:$hash"] = listOf(Sub(1, "en", machineTranslated = true))

            val result = finder.find(SubtitleRequest(SubtitleLanguage.ENGLISH, moviehash = hash))

            assertTrue(result is SubtitleSearch.NotFound)
            assertEquals(emptyList<String>(), downloads())
        }

    @Test
    fun `a Castilian subtitle from a later search beats a Latin-American hash match`() =
        runBlocking {
            fake.subtitles["hash:$hash"] = listOf(Sub(31, "ea", downloadCount = 5000))
            fake.subtitles["imdb:133093"] = listOf(Sub(32, "es"))
            serve(31, 32)

            val found = finder.find(movie) as SubtitleSearch.Found

            assertEquals(32L, found.subtitle.fileId)
            assertEquals(SubtitleVariant.CASTILIAN, found.subtitle.variant)
            assertTrue(searches()[0].contains("languages=ea%2Ces"))
        }

    @Test
    fun `Latin-American is the last resort, labelled latino, preferring its hash match`() =
        runBlocking {
            fake.subtitles["hash:$hash"] = listOf(Sub(41, "ea"))
            fake.subtitles["query:the matrix"] = listOf(Sub(42, "ea", downloadCount = 9000))
            serve(41, 42)

            val found = finder.find(movie) as SubtitleSearch.Found

            assertEquals(3, searches().size)
            assertEquals(41L, found.subtitle.fileId)
            assertEquals(SubtitleVariant.LATINO, found.subtitle.variant)
            assertEquals("latino", found.subtitle.label)
            assertEquals("ea", found.subtitle.languageCode)
        }

    @Test
    fun `nothing anywhere is NotFound with the quota as known`() =
        runBlocking {
            val result = finder.find(movie)

            assertEquals(SubtitleSearch.NotFound(Quota()), result)
            assertEquals(3, searches().size)
        }

    @Test
    fun `the result reports the remaining daily quota`() =
        runBlocking {
            fake.remaining = 8
            fake.subtitles["hash:$hash"] = listOf(Sub(11, "es"))
            serve(11)

            val found = finder.find(movie) as SubtitleSearch.Found

            assertEquals(Quota(remaining = 7, allowed = 20, resetsAt = Instant.parse(fake.resetTimeUtc)), found.quota)
        }

    @Test
    fun `a spent quota is reported as QuotaExhausted`() =
        runBlocking {
            fake.remaining = 0
            fake.subtitles["hash:$hash"] = listOf(Sub(11, "es"))

            val result = finder.find(movie)

            assertTrue(result is SubtitleSearch.QuotaExhausted)
            assertEquals(0, result.quota.remaining)
        }

    @Test
    fun `a failing search stops the lookup with the failure`() =
        runBlocking {
            fake.failSearchWith = HttpStatusCode.TooManyRequests

            assertEquals(SubtitleSearch.Failed(OsFailure.RateLimited, Quota()), finder.find(movie))
        }

    @Test
    fun `a Windows-1252 file arrives as UTF-8 text`() =
        runBlocking {
            fake.subtitles["hash:$hash"] = listOf(Sub(11, "es"))
            fake.files[11] =
                "1\n00:00:01,000 --> 00:00:02,000\n¿Qué pasó, señor?\n".toByteArray(charset("windows-1252"))

            val found = finder.find(movie) as SubtitleSearch.Found

            assertTrue(found.subtitle.text.contains("¿Qué pasó, señor?"))
            assertTrue(String(found.subtitle.utf8Bytes(), Charsets.UTF_8).contains("señor"))
        }
}
