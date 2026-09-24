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
}
