package com.teachermovies.assistant.subtitles

import com.teachermovies.player.api.Track
import java.util.Locale

/**
 * Picks the embedded subtitle track of a movie that most likely carries cues in a language, for the
 * hidden-mode fallback when no sidecar file exists.
 *
 * Candidates are ranked, case-insensitively: (1) [Track.language] equal to the requested code,
 * (2) [Track.language] starting with it (so `eng` matches `en`), (3) [Track.name] containing the
 * code in brackets (`[en]`) or the language's English name (`english`). Ties keep list order.
 */
object EmbeddedSubtitleTracks {
    fun pick(
        tracks: List<Track>,
        language: String = "en",
    ): Track? {
        val code = language.trim().lowercase(Locale.ROOT)
        if (code.isEmpty()) return null
        val englishName = englishNameOf(code)
        var best: Track? = null
        var bestRank = Int.MAX_VALUE
        for (track in tracks) {
            val rank = rankOf(track, code, englishName) ?: continue
            // Strictly better only: an equal rank keeps the earlier track (list-order tie-break).
            if (rank < bestRank) {
                best = track
                bestRank = rank
            }
        }
        return best
    }

    private fun rankOf(
        track: Track,
        code: String,
        englishName: String?,
    ): Int? {
        val declared = track.language?.trim()?.lowercase(Locale.ROOT)
        val name = track.name.lowercase(Locale.ROOT)
        return when {
            declared == code -> 1
            declared != null && declared.startsWith(code) -> 2
            name.contains("[$code]") -> 3
            englishName != null && name.contains(englishName) -> 3
            else -> null
        }
    }

    /** `english` for `en`; null when the JDK does not know the code (its display name is the code). */
    private fun englishNameOf(code: String): String? {
        val name = Locale(code).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT)
        return name.takeIf { it.isNotBlank() && it != code }
    }
}
