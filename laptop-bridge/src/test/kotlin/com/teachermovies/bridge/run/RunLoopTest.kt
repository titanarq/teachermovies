package com.teachermovies.bridge.run

import com.teachermovies.bridge.config.BridgeConfig
import com.teachermovies.bridge.config.BridgeConfigStore
import com.teachermovies.bridge.config.ConfigLoad
import com.teachermovies.bridge.protocol.BridgeJobDto
import com.teachermovies.bridge.protocol.BridgeJobResultDto
import com.teachermovies.bridge.protocol.TranslateJobDto
import com.teachermovies.bridge.tv.FakeTv
import com.teachermovies.bridge.tv.jsonField
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The `run` loop (#277) against [FakeTv]'s job stream and result route (#275): dispatch, the
 * immediate answer to a kind nobody handles, cancellation, reconnect with backoff, the 401 stop and
 * the mDNS fallback (with a fake [TvDiscovery]).
 */
class RunLoopTest {
    @get:Rule val tmpFolder = TemporaryFolder()

    private val tv = FakeTv()
    private val httpClient = HttpClient(CIO)
    private val api =
        com.teachermovies.bridge.tv
            .TvApi(httpClient)
    private val out = StringBuffer()
    private val sleeps: MutableList<Duration> = CopyOnWriteArrayList()

    @After
    fun tearDown() {
        httpClient.close()
        tv.close()
    }

    private fun store(): BridgeConfigStore = BridgeConfigStore(tmpFolder.root.toPath().resolve("config.json"))

    private fun paired(url: String = tv.baseUrl) = BridgeConfig(tvUrl = url, token = tv.token, deviceName = "portátil")

    private fun loop(
        registry: JobHandlerRegistry = JobHandlerRegistry.default(),
        discovery: TvDiscovery = TvDiscovery { emptyList() },
        store: BridgeConfigStore = store(),
    ) = RunLoop(
        api = api,
        store = store,
        registry = registry,
        log = RunLog(out, tmpFolder.root.toPath().resolve(RunLog.FILE_NAME)),
        discovery = discovery,
        backoff = Backoff(initial = 10.milliseconds, max = 40.milliseconds),
        sleep = {
            sleeps += it
            delay(it)
        },
    )

    private fun results() = tv.received.filter { it.method == "POST" && it.path.endsWith("/result") }

    private suspend fun eventually(condition: () -> Boolean) {
        withTimeout(WAIT_MILLIS) {
            while (!condition()) delay(POLL_MILLIS)
        }
    }

    private fun handler(
        kind: String,
        block: suspend (BridgeJobDto) -> BridgeJobResultDto,
    ) = object : JobHandler {
        override val kind = kind

        override suspend fun handle(job: BridgeJobDto) = block(job)
    }

    @Test
    fun `a job of a kind nobody handles is answered at once with an error result`() =
        runBlocking {
            val running = async { loop().run(paired()) }
            eventually { tv.streamsOpened.get() == 1 }
            assertTrue(tv.sendFrame("job", """{"kind":"translate","id":"job-1","line":"Hi"}"""))
            eventually { results().isNotEmpty() }
            running.cancel()
            val posted = results().single()
            assertEquals("/api/bridge/jobs/job-1/result", posted.path)
            assertEquals("Bearer ${tv.token}", posted.authorization)
            assertEquals("error", posted.body.jsonField("status"))
            assertEquals(JobHandlerRegistry.UNSUPPORTED_KIND, posted.body.jsonField("code"))
        }

    @Test
    fun `a job goes to its handler and the handler's result is posted back and logged`() =
        runBlocking {
            val seen = CompletableDeferred<BridgeJobDto>()
            val registry =
                JobHandlerRegistry(
                    listOf(
                        handler("translate") {
                            seen.complete(it)
                            BridgeJobResultDto.Done("Hola")
                        },
                    ),
                )
            val running = async { loop(registry).run(paired()) }
            eventually { tv.streamsOpened.get() == 1 }
            tv.sendFrame("job", """{"kind":"translate","id":"job-2","line":"Hi"}""")
            eventually { results().isNotEmpty() }
            running.cancel()
            assertEquals(TranslateJobDto(id = "job-2", line = "Hi"), seen.await())
            assertEquals("ok", results().single().body.jsonField("status"))
            assertEquals("Hola", results().single().body.jsonField("text"))
            val log = out.toString()
            assertTrue(log, log.contains("Trabajo translate job-2 recibido."))
            assertTrue(log, log.contains("Trabajo translate job-2: hecho en"))
            assertTrue(log, log.contains("respuesta entregada"))
            assertFalse("the job's text stays out of the log", log.contains("Hola"))
            val file =
                java.nio.file.Files
                    .readString(tmpFolder.root.toPath().resolve(RunLog.FILE_NAME))
            assertTrue(file.contains("Trabajo translate job-2 recibido."))
        }

    @Test
    fun `a slow job does not hold up the next one, and cancel stops it without an answer`() =
        runBlocking {
            val cancelled = CompletableDeferred<Unit>()
            val registry =
                JobHandlerRegistry(
                    listOf(
                        handler("explain") {
                            try {
                                awaitCancellation()
                            } finally {
                                cancelled.complete(Unit)
                            }
                        },
                        handler("translate") { BridgeJobResultDto.Done("Hola") },
                    ),
                )
            val running = async { loop(registry).run(paired()) }
            eventually { tv.streamsOpened.get() == 1 }
            tv.sendFrame(
                "job",
                """{"kind":"explain","id":"slow","title":null,"line":"x","before":[],"after":[],"spanishLine":null}""",
            )
            tv.sendFrame("job", """{"kind":"translate","id":"fast","line":"Hi"}""")
            eventually { results().any { it.path.contains("/fast/") } }
            tv.sendFrame("cancel", """{"id":"slow"}""")
            withTimeout(WAIT_MILLIS) { cancelled.await() }
            delay(POLL_MILLIS * 5)
            running.cancel()
            assertEquals(listOf("/api/bridge/jobs/fast/result"), results().map { it.path })
        }

    @Test
    fun `a dropped stream reconnects after a backoff that resets once connected`() =
        runBlocking {
            val running = async { loop().run(paired()) }
            eventually { tv.streamsOpened.get() == 1 }
            tv.closeStreams()
            eventually { tv.streamsOpened.get() == 2 }
            tv.closeStreams()
            eventually { tv.streamsOpened.get() == 3 }
            running.cancel()
            assertEquals(listOf(10.milliseconds, 10.milliseconds), sleeps.toList())
            assertTrue(out.toString().contains("La TV ha cerrado el canal de trabajos."))
        }

    @Test
    fun `a TV that stays down is retried with a growing backoff`() =
        runBlocking {
            val running = async { loop().run(paired(url = "http://127.0.0.1:${closedPort()}")) }
            eventually { sleeps.size >= 4 }
            running.cancel()
            assertEquals(listOf(10, 20, 40, 40).map { it.milliseconds }, sleeps.take(4))
        }

    @Test
    fun `a TV that refuses the token ends the loop`() =
        runBlocking {
            val end = withTimeout(WAIT_MILLIS) { loop().run(paired().copy(token = "otro")) }
            assertEquals(RunLoop.End.Unauthorized, end)
            assertTrue(sleeps.isEmpty())
        }

    @Test
    fun `an error status on the stream is retried, not fatal`() =
        runBlocking {
            tv.failWith = FakeTv.Answer(HttpStatusCode.ServiceUnavailable, "busy")
            val running = async { loop().run(paired()) }
            eventually { sleeps.size >= 2 }
            tv.failWith = null
            eventually { tv.streamsOpened.get() == 1 }
            running.cancel()
        }

    @Test
    fun `when the saved URL stops answering the TV is found over discovery and the new URL saved`() =
        runBlocking {
            val store = store()
            val dead = "http://127.0.0.1:${closedPort()}"
            store.save(paired(url = dead))
            val browsed = CopyOnWriteArrayList<Unit>()
            val discovery =
                TvDiscovery {
                    browsed += Unit
                    listOf(dead, tv.baseUrl)
                }
            val running = async { loop(discovery = discovery, store = store).run(paired(url = dead)) }
            eventually { tv.streamsOpened.get() == 1 }
            running.cancel()
            assertEquals("browses only after two failed attempts", 1, browsed.size)
            assertEquals(1, sleeps.size)
            assertEquals(tv.baseUrl, ((store.load() as ConfigLoad.Loaded).config.tvUrl))
            assertTrue(out.toString().contains("La TV ha cambiado de dirección: ahora ${tv.baseUrl}"))
        }

    @Test
    fun `a candidate that is not a TV never sees the token, and one that refuses it is skipped`() =
        runBlocking {
            val notTv = FakeTv().apply { failWith = FakeTv.Answer(HttpStatusCode.NotFound, "not_found") }
            val otherTv = FakeTv().apply { token = "token-de-otra-tv" }
            try {
                val dead = "http://127.0.0.1:${closedPort()}"
                val discovery = TvDiscovery { listOf(notTv.baseUrl, otherTv.baseUrl) }
                val running = async { loop(discovery = discovery).run(paired(url = dead)) }
                eventually { sleeps.size >= 3 }
                running.cancel()
                assertTrue(notTv.received.isNotEmpty())
                assertTrue(notTv.received.all { it.authorization == null && it.path == "/api/status" })
                assertTrue(otherTv.received.any { it.path == "/api/logs" })
                assertTrue(otherTv.received.none { it.path.startsWith("/api/bridge") })
                assertTrue(out.toString().contains("no acepta el token de este portátil"))
                assertTrue(out.toString().contains("No se ha encontrado la TV en la red local (2 candidatos)."))
            } finally {
                notTv.close()
                otherTv.close()
            }
        }

    @Test
    fun `a result the TV refuses is logged and the loop keeps going`() =
        runBlocking {
            tv.resultAnswer = FakeTv.Answer(HttpStatusCode.Conflict, "job_closed")
            val running = async { loop().run(paired()) }
            eventually { tv.streamsOpened.get() == 1 }
            tv.sendFrame("job", """{"kind":"translate","id":"job-3","line":"Hi"}""")
            eventually { out.toString().contains("la TV rechaza la respuesta (409 job_closed)") }
            tv.sendFrame("job", """{"kind":"translate","id":"job-4","line":"Hi"}""")
            eventually { results().size == 2 }
            running.cancel()
        }

    @Test
    fun `a frame without an id is ignored`() =
        runBlocking {
            val running = async { loop().run(paired()) }
            eventually { tv.streamsOpened.get() == 1 }
            tv.sendFrame("job", """{"kind":"translate"}""")
            tv.sendFrame("job", "no es json")
            tv.sendFrame("job", """{"kind":"translate","id":"job-5","line":"Hi"}""")
            eventually { results().isNotEmpty() }
            running.cancel()
            assertEquals(listOf("/api/bridge/jobs/job-5/result"), results().map { it.path })
            assertEquals(2, out.toString().split("Trabajo ilegible").size - 1)
        }

    private fun closedPort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    private companion object {
        const val WAIT_MILLIS = 5_000L
        const val POLL_MILLIS = 10L
    }
}
