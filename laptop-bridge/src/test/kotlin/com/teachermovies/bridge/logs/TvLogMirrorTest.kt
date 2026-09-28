package com.teachermovies.bridge.logs

import com.teachermovies.bridge.protocol.LogEntryDto
import com.teachermovies.bridge.run.Backoff
import com.teachermovies.bridge.run.RunLog
import com.teachermovies.bridge.tv.ApiFailure
import com.teachermovies.bridge.tv.JobStreamEnd
import com.teachermovies.bridge.tv.SseEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class TvLogMirrorTest {
    @get:Rule val tmpFolder = TemporaryFolder()

    /** One scripted stream: the frames it delivers, then how it ends. */
    private data class Script(
        val frames: List<SseEvent>,
        val end: JobStreamEnd = JobStreamEnd.Closed,
        val opens: Boolean = true,
    )

    /** Plays [scripts] in order, one per `open`, then refuses the token so `run` returns. */
    private class ScriptedSource(
        vararg scripts: Script,
    ) : LogStreamSource {
        private val pending = ArrayDeque(scripts.toList())
        val sinces = mutableListOf<Long?>()
        val urls = mutableListOf<String>()

        override suspend fun open(
            baseUrl: String,
            token: String,
            since: Long?,
            onOpen: suspend () -> Unit,
            onEvent: suspend (SseEvent) -> Boolean,
        ): JobStreamEnd {
            sinces += since
            urls += baseUrl
            val script = pending.removeFirstOrNull() ?: return JobStreamEnd.NotOpened(ApiFailure.Unauthorized)
            if (!script.opens) return script.end
            onOpen()
            for (frame in script.frames) {
                if (!onEvent(frame)) return JobStreamEnd.Closed
            }
            return script.end
        }
    }

    private val out = StringBuilder()
    private val sleeps = mutableListOf<Duration>()
    private val now = Instant.parse("2026-09-28T12:00:00Z")

    private val dir: Path by lazy { tmpFolder.root.toPath().resolve("tv-logs") }
    private val cursorFile: Path by lazy { dir.resolve(MirrorCursorStore.FILE_NAME) }

    private fun files(): TvLogFiles = TvLogFiles(dir, zone = ZoneOffset.UTC, clock = { now })

    private fun mirror(
        source: LogStreamSource,
        files: TvLogFiles = files(),
        backoff: Backoff = Backoff(max = TvLogMirror.MAX_BACKOFF),
    ): TvLogMirror =
        TvLogMirror(source, files, MirrorCursorStore(cursorFile), RunLog(out, null, clock = { now }), backoff) {
            sleeps += it
        }

    private fun runMirror(
        source: LogStreamSource,
        files: TvLogFiles = files(),
    ): TvLogMirror.End = runBlocking { mirror(source, files).run({ URL }, TOKEN) }

    private fun day(date: String): List<String> {
        val file = dir.resolve("$date.log")
        return if (Files.exists(file)) Files.readAllLines(file) else emptyList()
    }

    @Test
    fun `writes each entry as an ISO line in its own day's file`() {
        val source =
            ScriptedSource(
                Script(
                    listOf(
                        boot("A"),
                        log(1, "2026-09-27T23:59:59.500Z", "info", "torrent", "descarga completada"),
                        log(2, "2026-09-28T00:00:00.250Z", "warn", "player", "underrun\nde 240 ms"),
                        log(3, "2026-09-28T00:00:01Z", "error", "http server", "fallo"),
                    ),
                ),
            )

        assertEquals(TvLogMirror.End.Unauthorized, runMirror(source))

        assertEquals(listOf("2026-09-27T23:59:59.500Z I torrent descarga completada"), day("2026-09-27"))
        assertEquals(
            listOf(
                "2026-09-28T00:00:00.250Z W player underrun\\nde 240 ms",
                "2026-09-28T00:00:01.000Z E http_server fallo",
            ),
            day("2026-09-28"),
        )
        assertEquals(MirrorCursor("A", 3), MirrorCursorStore(cursorFile).load())
        assertEquals(
            "rw-------",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("2026-09-28.log"))),
        )
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
    }

    @Test
    fun `a restarted bridge backfills from the stored seq without duplicating or losing a line`() {
        runMirror(ScriptedSource(Script(listOf(boot("A"), log(1, T1), log(2, T1)))))

        // A new process: the cursor comes from disk. The TV replays seq 2 as well; it is dropped.
        val source = ScriptedSource(Script(listOf(boot("A"), log(2, T1), log(3, T1), log(4, T1))))
        runMirror(source)

        assertEquals(listOf<Long?>(2, 4), source.sinces)
        assertEquals(listOf("m1", "m2", "m3", "m4"), day("2026-09-28").map { it.substringAfterLast(' ') })
    }

    @Test
    fun `the first run asks for the whole buffer`() {
        val source = ScriptedSource(Script(listOf(boot("A"), log(1, T1))))

        runMirror(source)

        assertEquals(listOf<Long?>(null, 1), source.sinces)
    }

    @Test
    fun `a bootId change is marked with a gap line and the new boot is copied from seq 0`() {
        runMirror(ScriptedSource(Script(listOf(boot("boot-aaaaaaaa-1"), log(5, T1), log(6, T1)))))

        val source =
            ScriptedSource(
                // Opened with since=6: the new boot's seq 1..6 would be missing, so it is dropped...
                Script(listOf(boot("boot-bbbbbbbb-2"), log(7, T1, message = "no-debe-copiarse"))),
                // ...and reopened at once from 0, with no backoff.
                Script(listOf(boot("boot-bbbbbbbb-2"), log(1, T1), log(2, T1))),
            )
        runMirror(source)

        assertEquals(listOf<Long?>(6, 0, 2), source.sinces)
        val lines = day("2026-09-28")
        assertEquals(5, lines.size)
        assertEquals(listOf("m5", "m6"), lines.take(2).map { it.substringAfterLast(' ') })
        val gap = lines[2]
        assertTrue(gap, gap.startsWith("2026-09-28T12:00:00.000Z - bridge ====="))
        assertTrue(gap, gap.contains("boot-aaa -> boot-bbb"))
        assertEquals(listOf("m1", "m2"), lines.drop(3).map { it.substringAfterLast(' ') })
        assertFalse(lines.any { it.contains("no-debe-copiarse") })
        assertEquals(MirrorCursor("boot-bbbbbbbb-2", 2), MirrorCursorStore(cursorFile).load())
        assertTrue(out.toString().contains("La TV ha vuelto a arrancar"))
        // Only the stream that closed normally waits; the reopen after the gap does not.
        assertEquals(listOf(1.seconds), sleeps.drop(1))
    }

    @Test
    fun `a boot change on a stream already read from 0 needs no reopen`() {
        MirrorCursorStore(cursorFile).save(MirrorCursor("A", 0))
        val source = ScriptedSource(Script(listOf(boot("B"), log(1, T1))))

        runMirror(source)

        assertEquals(listOf<Long?>(0, 1), source.sinces)
        val lines = day("2026-09-28")
        assertTrue(lines[0].contains(" - bridge ====="))
        assertEquals("m1", lines[1].substringAfterLast(' '))
    }

    @Test
    fun `reconnect backoff doubles from 1 s and caps at 30 s, and resets once a stream opens`() {
        val down = Script(emptyList(), JobStreamEnd.NotOpened(ApiFailure.Network("timeout")), opens = false)
        val scripts = List(7) { down } + Script(listOf(boot("A"))) + down
        runMirror(ScriptedSource(*scripts.toTypedArray()))

        assertEquals(listOf(1, 2, 4, 8, 16, 30, 30, 1, 2).map { it.seconds }, sleeps)
        // The same problem is logged once, not once per attempt.
        assertEquals(2, out.lines().count { it.contains("La TV no responde al pedir el registro (timeout)") })
    }

    @Test
    fun `a 401 ends the mirror`() {
        val source = ScriptedSource(Script(emptyList(), JobStreamEnd.NotOpened(ApiFailure.Unauthorized), opens = false))

        assertEquals(TvLogMirror.End.Unauthorized, runMirror(source))

        assertTrue(sleeps.isEmpty())
        assertTrue(out.toString().contains("rechazado el token"))
        assertFalse(out.toString().contains(TOKEN))
    }

    @Test
    fun `a failed write keeps the cursor, so the retry asks for that line again`() {
        Files.createDirectories(dir)
        // A directory where today's file should be: appending to it fails.
        Files.createDirectories(dir.resolve("2026-09-28.log"))
        val source =
            ScriptedSource(
                Script(listOf(boot("A"), log(1, T1))),
                Script(listOf(boot("A"), log(1, T1))),
            )

        runMirror(source)

        // Kept in memory as (A, 0) after the boot frame, but never saved: nothing was written.
        assertEquals(listOf<Long?>(null, 0, 0), source.sinces)
        assertNull(MirrorCursorStore(cursorFile).load())
        assertTrue(out.toString().contains("No se puede escribir la copia del registro"))
        assertFalse(out.toString().contains("La TV ha cerrado el registro."))
    }

    @Test
    fun `undecodable frames and unknown events are skipped`() {
        val source =
            ScriptedSource(
                Script(listOf(boot("A"), SseEvent("log", "{roto"), SseEvent("otro", "{}"), log(1, T1))),
            )

        runMirror(source)

        assertEquals(listOf("m1"), day("2026-09-28").map { it.substringAfterLast(' ') })
        assertTrue(out.toString().contains("ilegible"))
    }

    @Test
    fun `the TV url is asked again before every attempt`() {
        val urls = ArrayDeque(listOf("http://a:8787", "http://b:8787"))
        val source = ScriptedSource(Script(listOf(boot("A"))))

        runBlocking { mirror(source).run({ urls.removeFirstOrNull() ?: "http://c:8787" }, TOKEN) }

        assertEquals(listOf("http://a:8787", "http://b:8787"), source.urls)
    }

    @Test
    fun `starting a new day's file deletes files older than 14 days`() {
        Files.createDirectories(dir)
        val old = dir.resolve("2026-09-13.log")
        val kept = dir.resolve("2026-09-14.log")
        val other = dir.resolve("notas.log")
        listOf(old, kept, other).forEach { Files.writeString(it, "x\n") }

        runMirror(ScriptedSource(Script(listOf(boot("A"), log(1, T1)))))

        assertFalse(Files.exists(old))
        assertTrue(Files.exists(kept))
        assertTrue(Files.exists(other))
    }

    @Test
    fun `the logs directory defaults to the working directory's cache and follows the variable`() {
        val home = tmpFolder.root.toPath()

        assertEquals(Path.of(".cache/tv-logs").toAbsolutePath().normalize(), TvLogFiles.defaultDir(home) { null })
        assertEquals(
            home.resolve("registros"),
            TvLogFiles.defaultDir(home) {
                if (it ==
                    TvLogFiles.DIR_VARIABLE
                ) {
                    "~/registros"
                } else {
                    null
                }
            },
        )
        assertEquals(Path.of(".cache/tv-logs").toAbsolutePath().normalize(), TvLogFiles.defaultDir(home) { " " })
    }

    @Test
    fun `level letters`() {
        assertEquals(
            listOf('D', 'I', 'W', 'E', '?'),
            listOf("debug", "info", "warn", "error", "").map(TvLogFiles::levelLetter),
        )
        assertEquals(dir.resolve("2026-09-28.log"), files().fileFor(LocalDate.parse("2026-09-28")))
    }

    private companion object {
        const val URL = "http://192.168.1.50:8787"
        const val TOKEN = "tok_super-secreto_9f3a"
        const val T1 = "2026-09-28T10:00:00Z"

        val JSON = Json

        fun boot(id: String): SseEvent = SseEvent("boot", "{\"bootId\":\"$id\"}")

        fun log(
            seq: Long,
            time: String,
            level: String = "info",
            module: String = "torrent",
            message: String = "m$seq",
        ): SseEvent =
            SseEvent(
                "log",
                JSON.encodeToString(
                    LogEntryDto.serializer(),
                    LogEntryDto(seq, Instant.parse(time).toEpochMilli(), level, module, message),
                ),
            )
    }
}
