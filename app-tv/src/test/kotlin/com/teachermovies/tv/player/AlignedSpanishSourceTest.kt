package com.teachermovies.tv.player

import com.teachermovies.assistant.EmbeddedSubtitle
import com.teachermovies.assistant.alignment.SpanishLineLookup
import com.teachermovies.assistant.alignment.SpanishSubtitleSource
import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.assistant.subtitles.SubtitleTrack
import com.teachermovies.core.model.SubtitleAlignment
import com.teachermovies.core.model.SubtitleFetch
import com.teachermovies.core.model.SubtitleFetchState
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.SubtitleAlignmentRepository
import com.teachermovies.core.repo.fake.InMemorySubtitleAlignmentRepository
import com.teachermovies.core.repo.fake.InMemorySubtitleFetchRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [SpanishSubtitleFinder] and [LookupAlignedSpanishSource] (#288) over real files and #283's lookup. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlignedSpanishSourceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val id = TorrentId("c".repeat(40))
    private val fetches = InMemorySubtitleFetchRepository()

    // Irregular timings, so only the right offset lines the two tracks up.
    private fun track(
        prefix: String,
        shiftMs: Long = 0,
    ) = SubtitleTrack(
        (0 until 60).map { i ->
            val start = i * 4_000L + (i % 3) * 700L + shiftMs
            SubtitleCue(i + 1, start, start + 1_500L + (i % 4) * 300L, "$prefix-$i")
        },
    )

    private val english = track("en")

    private fun srt(track: SubtitleTrack): String =
        track.cues.joinToString("\n") { "${it.index}\n${time(it.startMs)} --> ${time(it.endMs)}\n${it.text}\n" }

    private fun time(ms: Long) =
        "%02d:%02d:%02d,%03d".format(
            ms / 3_600_000,
            ms / 60_000 % 60,
            ms / 1_000 % 60,
            ms % 1_000,
        )

    private fun movie(): File = tmp.newFolder("Movies", id.value).resolve("Film.mkv").apply { writeText("x") }

    private suspend fun downloaded(
        path: File,
        variant: String? = null,
    ) {
        fetches.save(
            SubtitleFetch(
                torrentId = id,
                language = "es",
                state = SubtitleFetchState.Downloaded,
                movieHash = null,
                localPath = path.path,
                variantLabel = variant,
                attempts = 1,
                lastAttemptEpochMs = 1L,
                nextRetryEpochMs = null,
                errorMessage = null,
                updatedAtEpochMs = 1L,
            ),
        )
    }

    private val embeddedAsked = mutableListOf<String>()

    /** [embedded] answers only for the language code [embeddedLanguage]. */
    private fun TestScope.finder(
        embedded: EmbeddedSubtitle? = null,
        embeddedLanguage: String = "es",
    ) = SpanishSubtitleFinder(
        fetches,
        { _, language ->
            embeddedAsked += language
            embedded?.takeIf { language == embeddedLanguage }
        },
        UnconfinedTestDispatcher(testScheduler),
    )

    private fun TestScope.source(
        found: List<FoundSpanishSubtitle>,
        english: SubtitleTrack? = this@AlignedSpanishSourceTest.english,
        repository: SubtitleAlignmentRepository = InMemorySubtitleAlignmentRepository(),
    ) = LookupAlignedSpanishSource(
        SpanishLineLookup(repository, nowMs = { 7L }, dispatcher = UnconfinedTestDispatcher(testScheduler)),
        english = { english },
        find = { _, _ -> found },
    )

    @Test
    fun `sidecar names in Spanish are recognised and others are not`() {
        listOf("Film.es.srt", "film.ES.ass", "Other.spa.srt").forEach {
            assertTrue(it, SpanishSubtitleFinder.isSpanishSubtitleName(it))
        }
        listOf("Film.srt", "Film.en.srt", "Film.es.sub", "Films.srt").forEach {
            assertFalse(it, SpanishSubtitleFinder.isSpanishSubtitleName(it))
        }
    }

    @Test
    fun `the finder lists sidecar, embedded and downloaded Spanish tracks in that order`() =
        runTest {
            val movie = movie()
            movie.resolveSibling("Film.en.srt").writeText(srt(english))
            movie.resolveSibling("Film.es.srt").writeText(srt(track("es")))
            val downloadedFile =
                File(
                    tmp.newFolder("downloads"),
                    "film.os.es.srt",
                ).apply { writeText(srt(track("dl"))) }
            downloaded(downloadedFile, variant = "latino")
            val embeddedFile = tmp.newFile("Film.3.srt")

            val found = finder(EmbeddedSubtitle(embeddedFile, track("emb"))).find(id, movie)

            assertEquals(
                listOf(SpanishSubtitleSource.SIDECAR, SpanishSubtitleSource.EMBEDDED, SpanishSubtitleSource.DOWNLOADED),
                found.map { it.candidate.source },
            )
            assertEquals(movie.resolveSibling("Film.es.srt").absolutePath, found[0].candidate.path)
            assertEquals(
                "es-0",
                found[0]
                    .candidate.track.cues
                    .first()
                    .text,
            )
            assertEquals(listOf(false, false, true), found.map { it.latino })
        }

    @Test
    fun `an embedded track tagged spa is found when es finds none`() =
        runTest {
            val movie = movie()

            val found =
                finder(
                    EmbeddedSubtitle(tmp.newFile("Film.2.srt"), track("emb")),
                    embeddedLanguage = "spa",
                ).find(id, movie)

            assertEquals(listOf(SpanishSubtitleSource.EMBEDDED), found.map { it.candidate.source })
            assertEquals(listOf("es", "spa"), embeddedAsked)
        }

    @Test
    fun `a downloaded file lying next to the movie counts once, as the download`() =
        runTest {
            val movie = movie()
            val file = movie.resolveSibling("Film.es.srt").apply { writeText(srt(track("es"))) }
            downloaded(file, variant = "latino")

            val found = finder().find(id, movie)

            assertEquals(1, found.size)
            assertEquals(SpanishSubtitleSource.DOWNLOADED, found.single().candidate.source)
            assertTrue(found.single().latino)
        }

    @Test
    fun `missing, unreadable and not yet downloaded files are left out`() =
        runTest {
            val movie = movie()
            movie.resolveSibling("Film.es.srt").writeText("this is not a subtitle file")
            downloaded(File(tmp.root, "gone.es.srt"))
            fetches.save(fetches.get(id, "es")!!.copy(state = SubtitleFetchState.Searching))

            assertEquals(emptyList<FoundSpanishSubtitle>(), finder().find(id, movie))
        }

    @Test
    fun `a well aligned Spanish track answers the captured cue`() =
        runTest {
            val es =
                FoundSpanishSubtitle(candidate(SpanishSubtitleSource.SIDECAR, "/es.srt", track("es", shiftMs = 2_500)))

            val line = source(listOf(es)).lineFor(id, File("/m.mkv"), english.cues[30])

            assertEquals(AlignedSpanishLine("es-30", latino = false), line)
        }

    @Test
    fun `the Latin-American download is flagged latino`() =
        runTest {
            val es =
                FoundSpanishSubtitle(
                    candidate(SpanishSubtitleSource.DOWNLOADED, "/lat.srt", track("lat")),
                    latino = true,
                )

            assertEquals(
                AlignedSpanishLine("lat-12", latino = true),
                source(listOf(es)).lineFor(id, File("/m.mkv"), english.cues[12]),
            )
        }

    @Test
    fun `no trustworthy alignment, no Spanish track or no hidden mode answer null`() =
        runTest {
            val unrelated =
                SubtitleTrack(
                    (0 until 60).map { i ->
                        SubtitleCue(
                            i + 1,
                            i * 9_100L + 300L * (i % 7),
                            i * 9_100L + 300L * (i % 7) + 800L,
                            "x-$i",
                        )
                    },
                )
            val bad = FoundSpanishSubtitle(candidate(SpanishSubtitleSource.SIDECAR, "/bad.srt", unrelated))
            val good = FoundSpanishSubtitle(candidate(SpanishSubtitleSource.SIDECAR, "/es.srt", track("es")))

            assertNull(source(listOf(bad)).lineFor(id, File("/m.mkv"), english.cues[30]))
            assertNull(source(emptyList()).lineFor(id, File("/m.mkv"), english.cues[30]))
            assertNull(source(listOf(good), english = null).lineFor(id, File("/m.mkv"), english.cues[30]))
        }

    @Test
    fun `a failing alignment cache answers null so LEFT falls back to the bridge`() =
        runTest {
            val broken =
                object : SubtitleAlignmentRepository by InMemorySubtitleAlignmentRepository() {
                    override suspend fun get(
                        id: TorrentId,
                        subtitlePath: String,
                    ): SubtitleAlignment? = throw IllegalStateException("disk I/O")
                }
            val good = FoundSpanishSubtitle(candidate(SpanishSubtitleSource.SIDECAR, "/es.srt", track("es")))

            assertNull(source(listOf(good), repository = broken).lineFor(id, File("/m.mkv"), english.cues[30]))
        }

    private fun candidate(
        source: SpanishSubtitleSource,
        path: String,
        track: SubtitleTrack,
    ) = com.teachermovies.assistant.alignment
        .SpanishSubtitleCandidate(source, path, track)
}
