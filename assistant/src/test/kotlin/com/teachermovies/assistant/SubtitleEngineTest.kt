package com.teachermovies.assistant

import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.assistant.subtitles.SubtitleTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleEngineTest {
    private fun cue(
        index: Int,
        startMs: Long,
        endMs: Long,
    ) = SubtitleCue(index = index, startMs = startMs, endMs = endMs, text = "cue-$index")

    @Test
    fun `currentSubtitle stays null while no track is loaded, even as position moves`() =
        runTest {
            val position = MutableStateFlow(0L)
            val engine = SubtitleEngine(position, backgroundScope)
            runCurrent()

            assertNull(engine.currentSubtitle.value)

            position.value = 5_000L
            runCurrent()

            assertNull(engine.currentSubtitle.value)
        }

    @Test
    fun `currentSubtitle emits exactly once per cue change across playback, a gap, a backwards seek and track loads`() =
        runTest {
            val position = MutableStateFlow(0L)
            val engine = SubtitleEngine(position, backgroundScope)

            val emissions = mutableListOf<SubtitleCue?>()
            backgroundScope.launch { engine.currentSubtitle.collect { emissions.add(it) } }
            runCurrent()

            val trackA =
                SubtitleTrack(
                    cues =
                        listOf(
                            cue(0, startMs = 0L, endMs = 1_000L),
                            cue(1, startMs = 2_000L, endMs = 3_000L),
                        ),
                )
            engine.load(trackA)
            runCurrent()

            // Forward playback within the first cue: same cue, no new emission.
            position.value = 500L
            runCurrent()

            // Forward into the gap between cues.
            position.value = 1_500L
            runCurrent()

            // Still in the gap: still null, no new emission.
            position.value = 1_600L
            runCurrent()

            // Forward playback into the second cue.
            position.value = 2_500L
            runCurrent()

            // Backwards seek, back into the first cue.
            position.value = 500L
            runCurrent()

            // Loading a second track re-resolves against the last known position (500) right away.
            val trackB = SubtitleTrack(cues = listOf(cue(0, startMs = 0L, endMs = 800L)))
            engine.load(trackB)
            runCurrent()

            // Clearing the track sets currentSubtitle back to null right away.
            engine.load(null)
            runCurrent()

            assertEquals(
                listOf(
                    null, // subscription replay: no track loaded yet, position 0
                    trackA.cues[0], // load(trackA) resolves against position 0
                    null, // the gap at 1_500
                    trackA.cues[1], // forward into the second cue at 2_500
                    trackA.cues[0], // backwards seek to 500, back into the first cue
                    trackB.cues[0], // load(trackB) resolves against the last known position, 500
                    null, // load(null)
                ),
                emissions,
            )
        }

    @Test
    fun `load resolves immediately against the last known position without waiting for the next emission`() =
        runTest {
            val position = MutableStateFlow(1_200L)
            val engine = SubtitleEngine(position, backgroundScope)
            runCurrent()

            val track = SubtitleTrack(cues = listOf(cue(0, startMs = 1_000L, endMs = 2_000L)))
            engine.load(track)

            assertEquals(track.cues[0], engine.currentSubtitle.value)
        }

    private val captureTrack =
        SubtitleTrack(
            cues =
                listOf(
                    cue(0, startMs = 1_000L, endMs = 2_000L),
                    cue(1, startMs = 10_000L, endMs = 11_000L),
                ),
        )

    @Test
    fun `cueForCapture returns the cue containing the position`() =
        runTest {
            val engine = SubtitleEngine(MutableStateFlow(0L), backgroundScope)
            engine.load(captureTrack)

            assertEquals(captureTrack.cues[0], engine.cueForCapture(1_500L))
            assertEquals(captureTrack.cues[1], engine.cueForCapture(10_000L))
        }

    @Test
    fun `cueForCapture returns the previous cue in a gap no longer than maxGapMs`() =
        runTest {
            val engine = SubtitleEngine(MutableStateFlow(0L), backgroundScope)
            engine.load(captureTrack)

            assertEquals(captureTrack.cues[0], engine.cueForCapture(2_000L))
            assertEquals(captureTrack.cues[0], engine.cueForCapture(4_500L))
            // Exactly the default maxGapMs (3_000) after the end still counts.
            assertEquals(captureTrack.cues[0], engine.cueForCapture(5_000L))
        }

    @Test
    fun `cueForCapture returns null in a gap longer than maxGapMs, also after the last cue`() =
        runTest {
            val engine = SubtitleEngine(MutableStateFlow(0L), backgroundScope)
            engine.load(captureTrack)

            assertNull(engine.cueForCapture(5_001L))
            assertNull(engine.cueForCapture(20_000L))
        }

    @Test
    fun `cueForCapture honours a custom maxGapMs`() =
        runTest {
            val engine = SubtitleEngine(MutableStateFlow(0L), backgroundScope, maxGapMs = 500L)
            engine.load(captureTrack)

            assertEquals(captureTrack.cues[0], engine.cueForCapture(2_500L))
            assertNull(engine.cueForCapture(2_501L))
        }

    @Test
    fun `cueForCapture returns null before the first cue, for an empty track and with no track`() =
        runTest {
            val engine = SubtitleEngine(MutableStateFlow(0L), backgroundScope)

            assertNull(engine.cueForCapture(1_500L))

            engine.load(captureTrack)
            assertNull(engine.cueForCapture(999L))

            engine.load(SubtitleTrack(cues = emptyList()))
            assertNull(engine.cueForCapture(1_500L))
        }
}
