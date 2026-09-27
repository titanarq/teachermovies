package com.teachermovies.assistant.alignment

import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.assistant.subtitles.SubtitleTrack
import com.teachermovies.core.model.SubtitleAlignment
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.fake.InMemorySubtitleAlignmentRepository
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpanishLineLookupTest {
    private val torrent = TorrentId("0123456789abcdef0123456789abcdef01234567")
    private val english = SyntheticSubtitles.english()
    private val repository = InMemorySubtitleAlignmentRepository()

    private fun kotlinx.coroutines.test.TestScope.lookup() =
        SpanishLineLookup(repository, nowMs = { 42L }, dispatcher = UnconfinedTestDispatcher(testScheduler))

    private fun candidate(
        source: SpanishSubtitleSource,
        path: String,
        track: SubtitleTrack,
    ) = SpanishSubtitleCandidate(source, path, track)

    private val cue = english.cues[150]

    @Test
    fun `a good alignment returns the Spanish line of the captured cue`() =
        runTest {
            val es = SyntheticSubtitles.spanishOf(english, 6_100, 25.0 / 23.976)

            val result =
                lookup().lineFor(
                    torrent,
                    english,
                    cue,
                    listOf(candidate(SpanishSubtitleSource.DOWNLOADED, "/es.srt", es)),
                )

            result as SpanishLineResult.Found
            assertEquals("es-150", result.line.text)
            assertEquals(SpanishSubtitleSource.DOWNLOADED, result.line.source)
            assertEquals("/es.srt", result.line.subtitlePath)
            assertTrue(result.line.qualityScore >= 0.6)
        }

    @Test
    fun `a poor alignment gives no line`() =
        runTest {
            val unrelated = SyntheticSubtitles.english(seed = 99)

            val result =
                lookup().lineFor(
                    torrent,
                    english,
                    cue,
                    listOf(candidate(SpanishSubtitleSource.SIDECAR, "/x.srt", unrelated)),
                )

            assertEquals(SpanishLineResult.NoGoodAlignment, result)
        }

    @Test
    fun `no candidates gives no line`() =
        runTest {
            assertEquals(SpanishLineResult.NoGoodAlignment, lookup().lineFor(torrent, english, cue, emptyList()))
        }

    @Test
    fun `sidecar is preferred over embedded over downloaded whatever the list order`() =
        runTest {
            fun esTagged(tag: String) =
                SubtitleTrack(SyntheticSubtitles.spanishOf(english, 0).cues.map { it.copy(text = "$tag-${it.index}") })
            val candidates =
                listOf(
                    candidate(SpanishSubtitleSource.DOWNLOADED, "/d.srt", esTagged("d")),
                    candidate(SpanishSubtitleSource.EMBEDDED, "/e.srt", esTagged("e")),
                    candidate(SpanishSubtitleSource.SIDECAR, "/s.srt", esTagged("s")),
                )

            val all = lookup().lineFor(torrent, english, cue, candidates)
            val noSidecar = lookup().lineFor(torrent, english, cue, candidates.take(2))
            val downloadedOnly = lookup().lineFor(torrent, english, cue, candidates.take(1))

            assertEquals("s-150", (all as SpanishLineResult.Found).line.text)
            assertEquals("e-150", (noSidecar as SpanishLineResult.Found).line.text)
            assertEquals("d-150", (downloadedOnly as SpanishLineResult.Found).line.text)
        }

    @Test
    fun `a badly aligned preferred source falls through to the next one`() =
        runTest {
            val candidates =
                listOf(
                    candidate(SpanishSubtitleSource.SIDECAR, "/bad.srt", SyntheticSubtitles.english(seed = 99)),
                    candidate(
                        SpanishSubtitleSource.DOWNLOADED,
                        "/good.srt",
                        SyntheticSubtitles.spanishOf(english, -3_000),
                    ),
                )

            val result = lookup().lineFor(torrent, english, cue, candidates)

            assertEquals("/good.srt", (result as SpanishLineResult.Found).line.subtitlePath)
        }

    @Test
    fun `the computed alignment is cached with its computation time`() =
        runTest {
            val es = SyntheticSubtitles.spanishOf(english, 2_000)

            lookup().lineFor(torrent, english, cue, listOf(candidate(SpanishSubtitleSource.DOWNLOADED, "/es.srt", es)))

            val stored = repository.get(torrent, "/es.srt")!!
            assertTrue(kotlin.math.abs(stored.offsetMs - 2_000) <= 100)
            assertEquals(1.0, stored.frameRateScale, 0.0)
            assertEquals(42L, stored.computedAtEpochMs)
        }

    @Test
    fun `a cached alignment is used instead of recomputing`() =
        runTest {
            // The file really lines up at offset 0, but the cache says +5 s: the cached row must win.
            val es = SyntheticSubtitles.spanishOf(english, 0, jitterMs = 0)
            val shifted = english.cues[152]
            repository.save(SubtitleAlignment(torrent, "/es.srt", 5_000, 1.0, 0.9, 1L))
            val probe = SubtitleCue(999, shifted.startMs - 5_000, shifted.endMs - 5_000, "probe")

            val result =
                lookup().lineFor(
                    torrent,
                    english,
                    probe,
                    listOf(candidate(SpanishSubtitleSource.SIDECAR, "/es.srt", es)),
                )

            assertEquals("es-152", (result as SpanishLineResult.Found).line.text)
            assertEquals(1L, repository.get(torrent, "/es.srt")!!.computedAtEpochMs)
        }

    @Test
    fun `a cached poor score is not trusted`() =
        runTest {
            val es = SyntheticSubtitles.spanishOf(english, 0)
            repository.save(SubtitleAlignment(torrent, "/es.srt", 0, 1.0, 0.59, 1L))

            val result =
                lookup().lineFor(
                    torrent,
                    english,
                    cue,
                    listOf(candidate(SpanishSubtitleSource.SIDECAR, "/es.srt", es)),
                )

            assertEquals(SpanishLineResult.NoGoodAlignment, result)
        }

    @Test
    fun `a cue in a gap of a good alignment has no matching line`() =
        runTest {
            val es = SubtitleTrack(SyntheticSubtitles.spanishOf(english, 0).cues.filter { it.index != 150 })

            val result =
                lookup().lineFor(
                    torrent,
                    english,
                    cue,
                    listOf(candidate(SpanishSubtitleSource.SIDECAR, "/es.srt", es)),
                )

            assertEquals(SpanishLineResult.NoMatchingLine, result)
        }

    @Test
    fun `either cue of a joined pair finds the merged Spanish line`() =
        runTest {
            val (en, es) = SyntheticSubtitles.joinedPairs(offsetMs = -1_500)
            val candidates = listOf(candidate(SpanishSubtitleSource.DOWNLOADED, "/es.srt", es))

            val first = lookup().lineFor(torrent, en, en.cues[40], candidates)
            val second = lookup().lineFor(torrent, en, en.cues[41], candidates)

            assertEquals("es-20", (first as SpanishLineResult.Found).line.text)
            assertEquals("es-20", (second as SpanishLineResult.Found).line.text)
        }
}
