package com.teachermovies.player.policy

import com.teachermovies.player.api.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Table-driven: each test builds a small track list plus a persisted id, calls one
 * [TrackPolicy] function once, and checks the id it returns against the rule spelled out in
 * issue #76.
 */
class TrackPolicyTest {
    private fun track(
        id: String,
        name: String,
        language: String?,
    ) = Track(id = id, name = name, language = language)

    // -- audio() -------------------------------------------------------------------------------

    @Test
    fun audioWithNoTracksIsNull() {
        assertNull(TrackPolicy.audio(emptyList(), persistedId = null))
    }

    @Test
    fun audioWithNoTracksIsNullEvenWithAPersistedId() {
        assertNull(TrackPolicy.audio(emptyList(), persistedId = "a1"))
    }

    @Test
    fun audioPrefersThePersistedTrackOverAnEnglishOne() {
        val tracks =
            listOf(
                track("a1", "English 5.1", "eng"),
                track("a2", "Castellano", "spa"),
            )
        assertEquals("a2", TrackPolicy.audio(tracks, persistedId = "a2"))
    }

    @Test
    fun audioIgnoresAPersistedIdThatIsNotInTheList() {
        val tracks = listOf(track("a1", "English 5.1", "eng"), track("a2", "Castellano", "spa"))
        assertEquals("a1", TrackPolicy.audio(tracks, persistedId = "stale-id"))
    }

    @Test
    fun audioFallsBackToTheTrackWhoseLanguageCodeIsEnglish() {
        val tracks = listOf(track("a1", "Track 1", "spa"), track("a2", "Track 2", "eng"))
        assertEquals("a2", TrackPolicy.audio(tracks, persistedId = null))
    }

    @Test
    fun audioMatchesTheShortEnglishLanguageCodeToo() {
        val tracks = listOf(track("a1", "Track 1", "fra"), track("a2", "Track 2", "en"))
        assertEquals("a2", TrackPolicy.audio(tracks, persistedId = null))
    }

    @Test
    fun audioFallsBackToAnEnglishSoundingNameWhenLanguageIsUnknown() {
        val tracks = listOf(track("a1", "Castellano", null), track("a2", "English 5.1", null))
        assertEquals("a2", TrackPolicy.audio(tracks, persistedId = null))
    }

    @Test
    fun audioFallsBackToTheFirstTrackWhenNoneLooksEnglish() {
        val tracks = listOf(track("a1", "Castellano", "spa"), track("a2", "Francais", "fra"))
        assertEquals("a1", TrackPolicy.audio(tracks, persistedId = null))
    }

    @Test
    fun audioMatchingIsWordBoundedNotASubstringSearch() {
        // "Bengali" contains the letters "eng" but is not the English track; with nothing else
        // to prefer, the first track in the list wins.
        val tracks = listOf(track("a1", "Bengali", null), track("a2", "Castellano", "spa"))
        assertEquals("a1", TrackPolicy.audio(tracks, persistedId = null))
    }

    @Test
    fun audioMatchingIsCaseInsensitive() {
        val tracks = listOf(track("a1", "Castellano", "SPA"), track("a2", "ENGLISH 5.1", "ENG"))
        assertEquals("a2", TrackPolicy.audio(tracks, persistedId = null))
    }

    // -- subtitle() ----------------------------------------------------------------------------

    @Test
    fun subtitleWithNoPersistedIdIsOffByDefault() {
        val tracks = listOf(track("s1", "English", "eng"))
        assertNull(TrackPolicy.subtitle(tracks, persistedId = null))
    }

    @Test
    fun subtitleIsOffByDefaultEvenWhenAnEnglishTrackExists() {
        val tracks = listOf(track("s1", "Castellano", "spa"), track("s2", "English", "eng"))
        assertNull(TrackPolicy.subtitle(tracks, persistedId = null))
    }

    @Test
    fun subtitleReturnsThePersistedTrackWhenItIsPresent() {
        val tracks = listOf(track("s1", "English", "eng"), track("s2", "Castellano", "spa"))
        assertEquals("s2", TrackPolicy.subtitle(tracks, persistedId = "s2"))
    }

    @Test
    fun subtitleIgnoresAPersistedIdThatIsNotInTheList() {
        val tracks = listOf(track("s1", "English", "eng"))
        assertNull(TrackPolicy.subtitle(tracks, persistedId = "stale-id"))
    }

    @Test
    fun subtitleWithNoTracksIsNull() {
        assertNull(TrackPolicy.subtitle(emptyList(), persistedId = null))
    }

    @Test
    fun subtitleWithNoTracksIsNullEvenWithAPersistedId() {
        assertNull(TrackPolicy.subtitle(emptyList(), persistedId = "s1"))
    }
}
