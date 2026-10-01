package com.teachermovies.assistant.alignment

import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.assistant.subtitles.SubtitleTrack
import com.teachermovies.core.model.SubtitleAlignment
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.fake.InMemorySubtitleAlignmentRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SpanishCueTimelineTest {
    private val torrent = TorrentId("0123456789abcdef0123456789abcdef01234567")
    private val english = SubtitleTrack(listOf(SubtitleCue(0, 0, 1_000, "hi")))
    private val repository = InMemorySubtitleAlignmentRepository()
    private val lookup = SpanishLineLookup(repository, nowMs = { 1L })

    private fun es(vararg cues: Triple<Long, Long, String>) =
        SubtitleTrack(cues.mapIndexed { i, (s, e, t) -> SubtitleCue(i, s, e, t) })

    private fun candidate(
        source: SpanishSubtitleSource,
        path: String,
        track: SubtitleTrack,
    ) = SpanishSubtitleCandidate(source, path, track)

    private suspend fun cache(
        path: String,
        offsetMs: Long,
        scale: Double,
        quality: Double,
    ) = repository.save(SubtitleAlignment(torrent, path, offsetMs, scale, quality, 0L))

    @Test
    fun `offset shifts the position onto the Spanish timeline`() =
        runTest {
            cache("/a.srt", 500, 1.0, 0.9)
            val track = es(Triple(1_500L, 2_500L, "uno"), Triple(3_500L, 4_000L, "dos"))
            val timeline =
                SpanishCueTimeline.create(
                    lookup,
                    torrent,
                    english,
                    listOf(candidate(SpanishSubtitleSource.SIDECAR, "/a.srt", track)),
                )!!

            assertEquals("uno", timeline.textAt(1_000))
            assertEquals("uno", timeline.textAt(1_999))
            assertNull(timeline.textAt(2_000))
            assertEquals("dos", timeline.textAt(3_000))
            assertNull(timeline.textAt(0))
        }

    @Test
    fun `frame rate scale stretches the position`() =
        runTest {
            cache("/a.srt", 0, 2.0, 0.9)
            val track = es(Triple(10_000L, 12_000L, "x"))
            val timeline =
                SpanishCueTimeline.create(
                    lookup,
                    torrent,
                    english,
                    listOf(candidate(SpanishSubtitleSource.SIDECAR, "/a.srt", track)),
                )!!

            assertNull(timeline.textAt(4_999))
            assertEquals("x", timeline.textAt(5_000))
            assertEquals("x", timeline.textAt(5_999))
            assertNull(timeline.textAt(6_000))
        }

    @Test
    fun `a gap between cues gives null`() =
        runTest {
            cache("/a.srt", 0, 1.0, 0.9)
            val track = es(Triple(0L, 1_000L, "a"), Triple(5_000L, 6_000L, "b"))
            val timeline =
                SpanishCueTimeline.create(
                    lookup,
                    torrent,
                    english,
                    listOf(candidate(SpanishSubtitleSource.SIDECAR, "/a.srt", track)),
                )!!

            assertEquals("a", timeline.textAt(500))
            assertNull(timeline.textAt(3_000))
            assertEquals("b", timeline.textAt(5_000))
            assertNull(timeline.textAt(9_000))
        }

    @Test
    fun `no good alignment gives null`() =
        runTest {
            cache("/a.srt", 0, 1.0, 0.59)
            val track = es(Triple(0L, 1_000L, "a"))

            assertNull(
                SpanishCueTimeline.create(
                    lookup,
                    torrent,
                    english,
                    listOf(candidate(SpanishSubtitleSource.SIDECAR, "/a.srt", track)),
                ),
            )
            assertNull(SpanishCueTimeline.create(lookup, torrent, english, emptyList()))
        }

    @Test
    fun `candidates are tried sidecar then embedded then downloaded and a poor one is skipped`() =
        runTest {
            cache("/s.srt", 0, 1.0, 0.1)
            cache("/e.srt", 0, 1.0, 0.8)
            cache("/d.srt", 0, 1.0, 0.9)
            val candidates =
                listOf(
                    candidate(SpanishSubtitleSource.DOWNLOADED, "/d.srt", es(Triple(0L, 1_000L, "d"))),
                    candidate(SpanishSubtitleSource.EMBEDDED, "/e.srt", es(Triple(0L, 1_000L, "e"))),
                    candidate(SpanishSubtitleSource.SIDECAR, "/s.srt", es(Triple(0L, 1_000L, "s"))),
                )

            val timeline = SpanishCueTimeline.create(lookup, torrent, english, candidates)

            assertNotNull(timeline)
            assertEquals("e", timeline!!.textAt(10))
        }

    @Test
    fun `an empty Spanish track gives null text`() =
        runTest {
            cache("/a.srt", 0, 1.0, 0.9)
            val timeline =
                SpanishCueTimeline.create(
                    lookup,
                    torrent,
                    english,
                    listOf(candidate(SpanishSubtitleSource.SIDECAR, "/a.srt", SubtitleTrack(emptyList()))),
                )!!

            assertNull(timeline.textAt(0))
        }
}
