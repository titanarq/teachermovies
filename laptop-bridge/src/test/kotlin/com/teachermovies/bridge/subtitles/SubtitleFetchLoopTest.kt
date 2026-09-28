package com.teachermovies.bridge.subtitles

import com.teachermovies.bridge.config.BridgeConfig
import com.teachermovies.bridge.config.BridgeConfigStore
import com.teachermovies.bridge.opensubtitles.FetchedSubtitle
import com.teachermovies.bridge.opensubtitles.OsFailure
import com.teachermovies.bridge.opensubtitles.Quota
import com.teachermovies.bridge.opensubtitles.SubtitleLanguage
import com.teachermovies.bridge.opensubtitles.SubtitleRequest
import com.teachermovies.bridge.opensubtitles.SubtitleSearch
import com.teachermovies.bridge.opensubtitles.SubtitleSearcher
import com.teachermovies.bridge.opensubtitles.SubtitleVariant
import com.teachermovies.bridge.protocol.SubtitleNeedDto
import com.teachermovies.bridge.run.Backoff
import com.teachermovies.bridge.run.JobHandlerRegistry
import com.teachermovies.bridge.run.RunLog
import com.teachermovies.bridge.run.RunLoop
import com.teachermovies.bridge.run.TvDiscovery
import com.teachermovies.bridge.tv.FakeTv
import com.teachermovies.bridge.tv.TvApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The subtitle fetch loop (#282) against [FakeTv]'s subtitle routes (#280) and a scripted
 * [SubtitleSearcher] standing in for OpenSubtitles (#281): the three triggers -- the job stream
 * opening, a `subtitles-needed` frame, the 30-minute timer -- the catch-up of needs that piled up
 * while the laptop was off, `not_found` reported back, and the daily quota.
 */
class SubtitleFetchLoopTest {
    @get:Rule val tmpFolder = TemporaryFolder()

    private val tv = FakeTv()
    private val httpClient = HttpClient(CIO)
    private val api = TvApi(httpClient)
    private val out = StringBuffer()
    private val log by lazy { RunLog(out, tmpFolder.root.toPath().resolve(RunLog.FILE_NAME)) }
    private val searcher = ScriptedSearcher()
    private var clock: Instant = NOW

    @After
    fun tearDown() {
        httpClient.close()
        tv.close()
    }

    /** Answers each torrent id with what [answers] holds for it, NotFound otherwise. */
    private class ScriptedSearcher : SubtitleSearcher {
        val answers = mutableMapOf<String, (Quota) -> SubtitleSearch>()
        val asked: MutableList<SubtitleRequest> = CopyOnWriteArrayList()

        @Volatile var quota: Quota = Quota(remaining = 20, allowed = 20)

        /** Search requests carry the title, which the tests make equal to the torrent id. */
        override suspend fun find(request: SubtitleRequest): SubtitleSearch {
            asked += request
            return answers[request.title]?.invoke(quota) ?: SubtitleSearch.NotFound(quota)
        }

        override fun quota(): Quota = quota
    }

    private fun need(
        id: String,
        language: String = "es",
        hash: String? = "8e245d9679d31e12",
    ) = SubtitleNeedDto(
        torrentId = id,
        title = id,
        language = language,
        movieHash = hash,
        state = "pending",
        attempts = 0,
    )

    private fun found(
        text: String = "1\n00:00:01,000 --> 00:00:02,000\nHola\n",
        variant: SubtitleVariant = SubtitleVariant.CASTILIAN,
    ): (Quota) -> SubtitleSearch =
        { quota ->
            SubtitleSearch.Found(
                FetchedSubtitle(
                    variant = variant,
                    languageCode = if (variant == SubtitleVariant.LATINO) "ea" else "es",
                    fileId = 42,
                    fileName = "movie.srt",
                    release = null,
                    hashMatch = true,
                    text = text,
                ),
                quota,
            )
        }

    private fun fetchLoop(sleep: suspend (Duration) -> Unit = { delay(it) }) =
        SubtitleFetchLoop(api, searcher, log, sleep = sleep, now = { clock })

    private fun runLoop(fetch: SubtitleFetchLoop) =
        RunLoop(
            api = api,
            store = BridgeConfigStore(tmpFolder.root.toPath().resolve("config.json")),
            registry = JobHandlerRegistry.default(),
            log = log,
            discovery = TvDiscovery { emptyList() },
            backoff = Backoff(initial = 10.milliseconds, max = 40.milliseconds),
            subtitles = fetch,
        )

    private fun paired() = BridgeConfig(tvUrl = tv.baseUrl, token = tv.token, deviceName = "portátil")

    private fun statuses() = tv.subtitleStatuses.map { "${it.torrentId}/${it.language} ${it.status}" }

    private fun uploads() = tv.received.filter { it.path == "/api/bridge/subtitles" }

    private fun needsReads() = tv.received.count { it.path == "/api/bridge/subtitle-needs" }

    private suspend fun eventually(condition: () -> Boolean) {
        withTimeout(WAIT_MILLIS) {
            while (!condition()) delay(POLL_MILLIS)
        }
    }

    @Test
    fun `the job stream opening catches up on every need that piled up while the laptop was off`() =
        runBlocking {
            tv.subtitleNeeds += listOf(need("t1"), need("t2", language = "en"), need("t3"))
            searcher.answers["t1"] = found()
            searcher.answers["t3"] = found()
            val passes = CopyOnWriteArrayList<SubtitleFetchLoop.Pass>()
            val fetch = fetchLoop()
            val fetching = launch { fetch.run({ tv.baseUrl }, tv.token) { passes += it } }
            val running = launch { runLoop(fetch).run(paired()) }
            eventually { passes.isNotEmpty() }
            running.cancel()
            fetching.cancel()

            assertEquals(SubtitleFetchLoop.Pass(uploaded = 2, notFound = 1), passes.single())
            assertEquals(
                listOf(
                    "t1/es searching",
                    "t2/en searching",
                    "t2/en not_found",
                    "t3/es searching",
                ),
                statuses(),
            )
            assertEquals(2, uploads().size)
            assertTrue(tv.subtitleNeeds.isEmpty())
            assertEquals(
                listOf(SubtitleLanguage.SPANISH, SubtitleLanguage.ENGLISH, SubtitleLanguage.SPANISH),
                searcher.asked.map { it.language },
            )
            assertEquals("8e245d9679d31e12", searcher.asked.first().moviehash)
            assertTrue(out.contains("Subtítulos (${SubtitleFetchLoop.CONNECT}): 3 pendientes."))
        }

    @Test
    fun `a subtitles-needed frame runs another pass over the list`() =
        runBlocking {
            val passes = CopyOnWriteArrayList<SubtitleFetchLoop.Pass>()
            val fetch = fetchLoop()
            val fetching = launch { fetch.run({ tv.baseUrl }, tv.token) { passes += it } }
            val running = launch { runLoop(fetch).run(paired()) }
            eventually { passes.size == 1 }
            assertEquals(SubtitleFetchLoop.Pass(), passes.single())

            tv.subtitleNeeds += need("t9")
            searcher.answers["t9"] = found()
            assertTrue(tv.sendFrame("subtitles-needed", """{"torrentId":"t9"}"""))
            eventually { passes.size == 2 }
            running.cancel()
            fetching.cancel()

            assertEquals(SubtitleFetchLoop.Pass(uploaded = 1), passes[1])
            assertEquals(1, tv.streamsOpened.get())
            assertTrue(out.contains("Subtítulos (${SubtitleFetchLoop.NUDGE}): 1 pendientes."))
        }

    @Test
    fun `the timer runs a pass every 30 minutes without any other trigger`() =
        runBlocking {
            val ticks = Channel<Unit>()
            val sleeps = CopyOnWriteArrayList<Duration>()
            val passes = Channel<SubtitleFetchLoop.Pass>(Channel.UNLIMITED)
            val fetch =
                fetchLoop(sleep = {
                    sleeps += it
                    ticks.receive()
                })
            val fetching = launch { fetch.run({ tv.baseUrl }, tv.token) { passes.trySend(it) } }
            eventually { sleeps.size == 1 }
            assertEquals(0, needsReads())

            tv.subtitleNeeds += need("t1")
            ticks.send(Unit)
            assertEquals(SubtitleFetchLoop.Pass(notFound = 1), withTimeout(WAIT_MILLIS) { passes.receive() })
            ticks.send(Unit)
            assertEquals(SubtitleFetchLoop.Pass(), withTimeout(WAIT_MILLIS) { passes.receive() })
            fetching.cancel()

            assertEquals(listOf(30.minutes), sleeps.distinct())
            assertEquals(SubtitleFetchLoop.DEFAULT_INTERVAL, 30.minutes)
            assertEquals(2, needsReads())
            assertEquals(listOf("t1/es searching", "t1/es not_found"), statuses())
        }

    @Test
    fun `nothing found is reported to the TV as not_found and nothing is uploaded`() =
        runBlocking {
            tv.subtitleNeeds += need("t1", hash = null)
            val pass = fetchLoop().pass(tv.baseUrl, tv.token, SubtitleFetchLoop.TIMER)

            assertEquals(SubtitleFetchLoop.Pass(notFound = 1), pass)
            assertEquals(listOf("t1/es searching", "t1/es not_found"), statuses())
            assertNull(tv.subtitleStatuses.last().message)
            assertTrue(uploads().isEmpty())
            assertNull(searcher.asked.single().moviehash)
            assertEquals("t1", searcher.asked.single().title)
        }

    @Test
    fun `a found subtitle is uploaded with its language, variant and text, bearer in the header`() =
        runBlocking {
            tv.subtitleNeeds += need("t1")
            searcher.answers["t1"] =
                found(text = "1\n00:00:01,000 --> 00:00:02,000\nÁnimo\n", variant = SubtitleVariant.LATINO)
            val pass = fetchLoop().pass(tv.baseUrl, tv.token, SubtitleFetchLoop.TIMER)

            assertEquals(SubtitleFetchLoop.Pass(uploaded = 1), pass)
            val upload = uploads().single()
            assertEquals("Bearer ${tv.token}", upload.authorization)
            assertTrue(upload.body.contains("name=torrentId"))
            assertTrue(upload.body.contains("t1"))
            assertTrue(upload.body.contains("name=language"))
            assertTrue(upload.body.contains("name=variant"))
            assertTrue(upload.body.contains("latino"))
            assertTrue(upload.body.contains("Ánimo"))
            assertEquals(listOf("t1/es searching"), statuses())
        }

    @Test
    fun `a spent quota stops the pass, leaves the rest listed and waits for the reset`() =
        runBlocking {
            tv.subtitleNeeds += listOf(need("t1"), need("t2"), need("t3"))
            searcher.answers["t1"] = found()
            val reset = NOW.plusSeconds(3_600)
            searcher.answers["t2"] = {
                searcher.quota = Quota(remaining = 0, allowed = 20, resetsAt = reset)
                SubtitleSearch.QuotaExhausted(searcher.quota)
            }
            val fetch = fetchLoop()
            val first = fetch.pass(tv.baseUrl, tv.token, SubtitleFetchLoop.CONNECT)

            assertEquals(
                SubtitleFetchLoop.Pass(uploaded = 1, failed = 1, stoppedBy = SubtitleFetchLoop.Stop.QUOTA),
                first,
            )
            assertEquals(listOf("t1/es searching", "t2/es searching", "t2/es failed"), statuses())
            assertEquals("cuota diaria de OpenSubtitles agotada", tv.subtitleStatuses.last().message)
            assertEquals(listOf("t3", "t2"), tv.subtitleNeeds.map { it.torrentId })
            assertEquals(listOf("t1", "t2"), searcher.asked.map { it.title })

            // Before the reset a pass spends nothing: it does not even read the list.
            val reads = needsReads()
            val waiting = fetch.pass(tv.baseUrl, tv.token, SubtitleFetchLoop.TIMER)
            assertEquals(SubtitleFetchLoop.Pass(stoppedBy = SubtitleFetchLoop.Stop.QUOTA), waiting)
            assertEquals(reads, needsReads())
            assertEquals(2, searcher.asked.size)

            // After it, the rest is picked up.
            clock = reset.plusSeconds(1)
            searcher.answers.remove("t2")
            val after = fetch.pass(tv.baseUrl, tv.token, SubtitleFetchLoop.TIMER)
            assertEquals(SubtitleFetchLoop.Pass(notFound = 2), after)
        }

    @Test
    fun `a quota that runs out on a download stops before the next need`() =
        runBlocking {
            tv.subtitleNeeds += listOf(need("t1"), need("t2"))
            searcher.answers["t1"] = { _ ->
                searcher.quota = Quota(remaining = 0, allowed = 20, resetsAt = NOW.plusSeconds(60))
                found()(searcher.quota)
            }
            val pass = fetchLoop().pass(tv.baseUrl, tv.token, SubtitleFetchLoop.TIMER)

            assertEquals(SubtitleFetchLoop.Pass(uploaded = 1, stoppedBy = SubtitleFetchLoop.Stop.QUOTA), pass)
            assertEquals(listOf("t1/es searching"), statuses())
            assertEquals(listOf("t2"), tv.subtitleNeeds.map { it.torrentId })
            assertTrue(out.contains("quedan 0 descargas hoy; cuota agotada"))
        }

    @Test
    fun `refused credentials fail the need and stop the pass, without leaking anything`() =
        runBlocking {
            tv.subtitleNeeds += listOf(need("t1"), need("t2"))
            searcher.answers["t1"] = { SubtitleSearch.Failed(OsFailure.LoginRefused, it) }
            val pass = fetchLoop().pass(tv.baseUrl, tv.token, SubtitleFetchLoop.TIMER)

            assertEquals(SubtitleFetchLoop.Pass(failed = 1, stoppedBy = SubtitleFetchLoop.Stop.OPENSUBTITLES), pass)
            assertEquals(listOf("t1/es searching", "t1/es failed"), statuses())
            assertEquals("OpenSubtitles rechaza el usuario o la contraseña", tv.subtitleStatuses.last().message)
            assertEquals(listOf("t2", "t1"), tv.subtitleNeeds.map { it.torrentId })
        }

    @Test
    fun `a network failure on one need fails it and goes on with the next`() =
        runBlocking {
            tv.subtitleNeeds += listOf(need("t1"), need("t2"))
            searcher.answers["t1"] = { SubtitleSearch.Failed(OsFailure.Network("timeout"), it) }
            val pass = fetchLoop().pass(tv.baseUrl, tv.token, SubtitleFetchLoop.TIMER)

            assertEquals(SubtitleFetchLoop.Pass(notFound = 1, failed = 1), pass)
            assertEquals("OpenSubtitles no responde (timeout)", tv.subtitleStatuses[1].message)
        }

    @Test
    fun `an upload the TV refuses is reported failed`() =
        runBlocking {
            tv.subtitleNeeds += need("t1")
            searcher.answers["t1"] = found()
            tv.uploadAnswer = FakeTv.Answer(HttpStatusCode.PayloadTooLarge, "too_large")
            val pass = fetchLoop().pass(tv.baseUrl, tv.token, SubtitleFetchLoop.TIMER)

            assertEquals(SubtitleFetchLoop.Pass(failed = 1), pass)
            assertEquals(listOf("t1/es searching", "t1/es failed"), statuses())
            assertEquals("la TV no ha aceptado el fichero (413 too_large)", tv.subtitleStatuses.last().message)
        }

    @Test
    fun `a language the bridge does not fetch is failed without a search`() =
        runBlocking {
            tv.subtitleNeeds += need("t1", language = "fr")
            val pass = fetchLoop().pass(tv.baseUrl, tv.token, SubtitleFetchLoop.TIMER)

            assertEquals(SubtitleFetchLoop.Pass(failed = 1), pass)
            assertEquals(listOf("t1/fr failed"), statuses())
            assertTrue(searcher.asked.isEmpty())
        }

    @Test
    fun `a list the TV will not serve ends the pass with nothing searched`() =
        runBlocking {
            tv.subtitleNeeds += need("t1")
            tv.failWith = FakeTv.Answer(HttpStatusCode.ServiceUnavailable, "unavailable")
            val pass = fetchLoop().pass(tv.baseUrl, tv.token, SubtitleFetchLoop.TIMER)

            assertEquals(SubtitleFetchLoop.Pass(stoppedBy = SubtitleFetchLoop.Stop.NO_LIST), pass)
            assertTrue(searcher.asked.isEmpty())
            assertTrue(out.contains("no se puede leer la lista de la TV (503 unavailable)"))
        }

    @Test
    fun `a need the TV refuses to hand out is skipped without searching`() =
        runBlocking {
            tv.subtitleNeeds += need("t1")
            tv.statusAnswer = FakeTv.Answer(HttpStatusCode.NotFound, "unknown_need")
            val pass = fetchLoop().pass(tv.baseUrl, tv.token, SubtitleFetchLoop.TIMER)

            assertEquals(SubtitleFetchLoop.Pass(skipped = 1), pass)
            assertTrue(searcher.asked.isEmpty())
            assertTrue(out.contains("la TV no acepta la búsqueda (404 unknown_need); se omite."))
        }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-28T10:00:00Z")
        const val WAIT_MILLIS = 5_000L
        const val POLL_MILLIS = 10L
    }
}
