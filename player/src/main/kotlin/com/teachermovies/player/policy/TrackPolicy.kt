package com.teachermovies.player.policy

import com.teachermovies.player.api.Track

/**
 * Which audio and subtitle track a screen should hand [com.teachermovies.player.api.Player] when
 * a movie is opened, so that decision is a pure, tested function instead of logic scattered
 * across the UI layer.
 *
 * Pure Kotlin, no `Player` or libVLC type in sight: it only ever sees [Track] and the persisted
 * track id `:core-model` stored for the item.
 */
object TrackPolicy {
    /**
     * Matches a language code or a track name that says "this is English": `en`/`eng` as a
     * language code, or the word `english` in a name like "English 5.1". Word-bounded so it
     * doesn't fire on an unrelated word that merely contains those letters (e.g. "Bengali").
     */
    private val englishPattern = Regex("""\b(en|eng|english)\b""", RegexOption.IGNORE_CASE)

    /**
     * The audio track to select: [persistedId] when it names one of [tracks]; otherwise the
     * first track that looks English by language or name; otherwise the first track of the list;
     * `null` when [tracks] is empty.
     */
    fun audio(
        tracks: List<Track>,
        persistedId: String?,
    ): String? {
        if (tracks.isEmpty()) return null
        if (persistedId != null && tracks.any { it.id == persistedId }) return persistedId
        return (tracks.firstOrNull { it.looksEnglish() } ?: tracks.first()).id
    }

    /**
     * The subtitle track to select: [persistedId] when it names one of [tracks]; otherwise
     * `null` -- subtitles are off by default, unlike audio, which always has some track playing.
     */
    fun subtitle(
        tracks: List<Track>,
        persistedId: String?,
    ): String? {
        if (persistedId != null && tracks.any { it.id == persistedId }) return persistedId
        return null
    }

    private fun Track.looksEnglish(): Boolean =
        (language != null && englishPattern.containsMatchIn(language)) || englishPattern.containsMatchIn(name)
}
