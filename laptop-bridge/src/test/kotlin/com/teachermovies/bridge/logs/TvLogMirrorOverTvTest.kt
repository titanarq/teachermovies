package com.teachermovies.bridge.logs

import com.teachermovies.bridge.protocol.LogEntryDto
import com.teachermovies.bridge.protocol.LogsPageDto
import com.teachermovies.bridge.run.Backoff
import com.teachermovies.bridge.run.RunLog
import com.teachermovies.bridge.tv.FakeTv
import com.teachermovies.bridge.tv.TvApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds

/** [TvLogMirror] over the real [TvApi.logStream] against [FakeTv]: backlog, live lines, a TV restart. */
class TvLogMirrorOverTvTest {
    @get:Rule val tmpFolder = TemporaryFolder()

    private val tv = FakeTv()
    private val httpClient = HttpClient(CIO)
    private val api = TvApi(httpClient)

    @After
    fun tearDown() {
        httpClient.close()
        tv.close()
    }

    @Test
    fun `copies the backlog and live lines, and marks a TV restart before its new lines`() =
        runBlocking {
            val dir = tmpFolder.root.toPath().resolve("tv-logs")
            val out = StringBuilder()
            val mirror =
                TvLogMirror(
                    LogStreamSource.of(api),
                    TvLogFiles(dir, zone = ZoneOffset.UTC, clock = { NOW }),
                    MirrorCursorStore(dir.resolve(MirrorCursorStore.FILE_NAME)),
                    RunLog(out, null),
                    Backoff(10.milliseconds, 30.milliseconds),
                )
            val today = dir.resolve("2023-11-14.log")

            withTimeout(10_000) {
                val running = async { mirror.run({ tv.baseUrl }, tv.token) }
                awaitLines(today, 2)
                assertTrue(tv.sendLog(entry(9, "en directo")))
                awaitLines(today, 3)

                // The TV restarts: a new boot whose numbering starts over, and the old stream closes.
                tv.logsPage = LogsPageDto("boot-nuevo-5678", listOf(entry(1, "primera tras reiniciar")))
                tv.closeStreams()
                awaitLines(today, 5)

                tv.token = "token-renovado"
                tv.closeStreams()
                assertEquals(TvLogMirror.End.Unauthorized, running.await())
            }

            val lines = Files.readAllLines(today)
            assertEquals("2023-11-14T22:13:20.000Z I torrent descarga completada", lines[0])
            assertEquals("2023-11-14T22:13:21.000Z W player underrun de 240 ms", lines[1])
            assertTrue(lines[2], lines[2].endsWith(" I torrent en directo"))
            assertTrue(
                lines[3],
                lines[3].contains(" - bridge ===== la TV ha vuelto a arrancar (arranque boot-123 -> boot-nue)"),
            )
            assertTrue(lines[4], lines[4].endsWith(" I torrent primera tras reiniciar"))
            assertEquals(
                MirrorCursor("boot-nuevo-5678", 1),
                MirrorCursorStore(dir.resolve(MirrorCursorStore.FILE_NAME)).load(),
            )
            // The restart was seen with since=9, so the new boot was read again from 0.
            val sinces = tv.received.filter { it.path == "/api/logs/stream" }.map { it.query }
            assertEquals(listOf("", "since=9", "since=0", "since=1"), sinces)
        }

    private suspend fun awaitLines(
        file: Path,
        count: Int,
    ) {
        while (!Files.exists(file) || Files.readAllLines(file).size < count) delay(10)
    }

    private companion object {
        /** The day of FakeTv's default entries (1_700_000_000_000 ms), so every line lands in one file. */
        val NOW: Instant = Instant.parse("2023-11-14T23:00:00Z")

        fun entry(
            seq: Long,
            message: String,
        ): LogEntryDto = LogEntryDto(seq, 1_700_000_002_000L, "info", "torrent", message)
    }
}
