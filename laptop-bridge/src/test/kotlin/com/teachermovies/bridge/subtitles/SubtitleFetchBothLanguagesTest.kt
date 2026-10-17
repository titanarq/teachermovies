package com.teachermovies.bridge.subtitles

import com.teachermovies.bridge.opensubtitles.FakeOpenSubtitles
import com.teachermovies.bridge.opensubtitles.FakeOpenSubtitles.Sub
import com.teachermovies.bridge.opensubtitles.OpenSubtitlesApi
import com.teachermovies.bridge.opensubtitles.OpenSubtitlesCredentials
import com.teachermovies.bridge.opensubtitles.SubtitleFinder
import com.teachermovies.bridge.protocol.SubtitleNeedDto
import com.teachermovies.bridge.run.RunLog
import com.teachermovies.bridge.tv.FakeTv
import com.teachermovies.bridge.tv.TvApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The real [SubtitleFinder] and [SubtitleFetchLoop] over fake OpenSubtitles and a fake TV: a movie
 * with both of the TV's needs (`es` and `en`, as SubtitleNeedsCoordinator writes them) has BOTH
 * languages searched and uploaded, and one language finding nothing does not stop the other.
 */
class SubtitleFetchBothLanguagesTest {
    @get:Rule val tmpFolder = TemporaryFolder()

    private val os = FakeOpenSubtitles()
    private val tv = FakeTv()
    private val httpClient = HttpClient(CIO)
    private val api = TvApi(httpClient)
    private val log by lazy { RunLog(StringBuffer(), tmpFolder.root.toPath().resolve(RunLog.FILE_NAME)) }
    private val finder =
        SubtitleFinder(
            OpenSubtitlesApi(
                httpClient,
                OpenSubtitlesCredentials(os.apiKey, os.username, os.password),
                baseUrl = os.apiUrl,
            ),
        )

    private val hash = "8e245d9679d31e12"

    @After
    fun tearDown() {
        httpClient.close()
        tv.close()
        os.close()
    }

    private fun need(language: String) =
        SubtitleNeedDto("t1", "The Matrix", language, hash, state = "pending", attempts = 0)

    private fun serve(vararg ids: Long) =
        ids.forEach {
            os.files[it] =
                "1\n00:00:01,000 --> 00:00:02,000\nx $it\n".toByteArray()
        }

    private fun searchQueries() = os.received.filter { it.path == "/api/v1/subtitles" }.map { it.query }

    private fun uploads() = tv.received.filter { it.path == "/api/bridge/subtitles" }

    private fun uploadedLanguages() =
        uploads().map { req ->
            Regex("name=language[\\s\\S]*?\\b(en|es)\\b").find(req.body)?.groupValues?.get(1)
        }

    private fun loop() = SubtitleFetchLoop(api, finder, log)

    @Test
    fun `both needs of one movie are searched with their own language and both uploaded`() =
        runBlocking {
            os.subtitles["hash:$hash"] = listOf(Sub(1, "en"), Sub(2, "es"))
            serve(1, 2)
            tv.subtitleNeeds += listOf(need("es"), need("en"))

            val pass = loop().pass(tv.baseUrl, tv.token, SubtitleFetchLoop.TIMER)

            assertEquals(SubtitleFetchLoop.Pass(uploaded = 2), pass)
            val queries = searchQueries()
            assertEquals(2, queries.size)
            assertTrue(
                queries.toString(),
                queries.any {
                    it.contains("languages=ea%2Ces%2Csp") ||
                        it.contains("languages=ea,es,sp")
                },
            )
            assertTrue(queries.toString(), queries.any { it.contains("languages=en") })
            assertEquals(2, uploads().size)
            assertEquals(setOf("en", "es"), uploadedLanguages().toSet())
        }

    @Test
    fun `an English need that finds nothing does not stop the Spanish one`() =
        runBlocking {
            os.subtitles["hash:$hash"] = listOf(Sub(2, "es"))
            serve(2)
            tv.subtitleNeeds += listOf(need("en"), need("es"))

            val pass = loop().pass(tv.baseUrl, tv.token, SubtitleFetchLoop.TIMER)

            assertEquals(SubtitleFetchLoop.Pass(uploaded = 1, notFound = 1), pass)
            assertEquals(1, uploads().size)
            assertTrue(tv.subtitleStatuses.any { it.language == "en" && it.status == "not_found" })
        }

    @Test
    fun `a Spanish need with no hash match falls back to the title and is still uploaded`() =
        runBlocking {
            os.subtitles["query:the matrix"] = listOf(Sub(3, "es", hashMatch = false))
            serve(3)
            tv.subtitleNeeds += listOf(need("en"), need("es"))

            val pass = loop().pass(tv.baseUrl, tv.token, SubtitleFetchLoop.TIMER)

            assertEquals(SubtitleFetchLoop.Pass(uploaded = 1, notFound = 1), pass)
            assertEquals(1, uploads().size)
            assertTrue(tv.subtitleStatuses.any { it.language == "en" && it.status == "not_found" })
        }
}
