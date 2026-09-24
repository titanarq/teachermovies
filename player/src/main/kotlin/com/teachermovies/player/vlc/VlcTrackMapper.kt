package com.teachermovies.player.vlc

import com.teachermovies.player.api.Player
import com.teachermovies.player.api.Track
import java.util.Locale

/**
 * Turns the two parallel arrays libVLC reports for its tracks -- ids and display labels -- into the
 * [Track] values of `com.teachermovies.player.api`.
 *
 * libVLC knows a track only as an `Int` id and as a label written for its own menu: `English [en]`,
 * `Track 2 - [Spanish]`, `Track 1`. Reading a language out of those labels is guessing, so it lives
 * here rather than in the libVLC adapter: this object touches no `org.videolan` type, which is what
 * lets it be unit tested on the JVM without loading libVLC (#75).
 *
 * [Track.id] is the libVLC id in decimal, the value the caller hands back to [Player.selectAudio]
 * and [Player.selectSubtitle]; [Track.name] is the label unchanged, because that is what the user
 * recognises; [Track.language] is an ISO-639 tag read out of the label, null when the label carries
 * no language -- which is what `Track`'s contract already expects of real-world media.
 */
internal object VlcTrackMapper {
    /**
     * The pseudo-track libVLC lists among the subtitle tracks to mean "subtitles off". It is not a
     * track of the media, so it never reaches the UI: turning subtitles off is
     * [Player.selectSubtitle]`(null)`.
     */
    const val DISABLE_TRACK_ID = -1

    /**
     * Pairs [ids] with [names] position by position, the way libVLC returns them, dropping the
     * [DISABLE_TRACK_ID] entry. Entries past the end of the shorter array are ignored.
     */
    fun map(
        ids: IntArray,
        names: Array<String>,
    ): List<Track> =
        (0 until minOf(ids.size, names.size))
            .filterNot { ids[it] == DISABLE_TRACK_ID }
            .map { index ->
                Track(
                    id = ids[index].toString(),
                    name = names[index],
                    language = languageOf(names[index]),
                )
            }

    /**
     * Language names worth resolving to a code, in lower case: what a label spells out instead of a
     * tag (`Track 2 - [Spanish]`). Deliberately not every language there is -- anything outside this
     * table yields a null language, and the label still shows the user which track it is.
     */
    private val CODES_BY_LANGUAGE_NAME =
        mapOf(
            "arabic" to "ar",
            "basque" to "eu",
            "bengali" to "bn",
            "bosnian" to "bs",
            "brazilian portuguese" to "pt-br",
            "bulgarian" to "bg",
            "catalan" to "ca",
            "chinese" to "zh",
            "croatian" to "hr",
            "czech" to "cs",
            "danish" to "da",
            "dutch" to "nl",
            "english" to "en",
            "finnish" to "fi",
            "french" to "fr",
            "galician" to "gl",
            "german" to "de",
            "greek" to "el",
            "hebrew" to "he",
            "hindi" to "hi",
            "hungarian" to "hu",
            "icelandic" to "is",
            "indonesian" to "id",
            "italian" to "it",
            "japanese" to "ja",
            "korean" to "ko",
            "malay" to "ms",
            "norwegian" to "no",
            "persian" to "fa",
            "polish" to "pl",
            "portuguese" to "pt",
            "romanian" to "ro",
            "russian" to "ru",
            "serbian" to "sr",
            "simplified chinese" to "zh-cn",
            "slovak" to "sk",
            "slovenian" to "sl",
            "spanish" to "es",
            "swedish" to "sv",
            "thai" to "th",
            "traditional chinese" to "zh-tw",
            "turkish" to "tr",
            "ukrainian" to "uk",
            "urdu" to "ur",
            "vietnamese" to "vi",
        )

    /** The `[...]` part of a label, which libVLC fills with either a tag or a language name. */
    private val BRACKETED_PART = Regex("""\[\s*([^]]*?)\s*]""")

    /** The `Track 2 - ` part of a label libVLC invented because the media named no language. */
    private val GENERIC_TRACK_PREFIX = Regex("""^track\s*\d+\s*[-–—:]?\s*""", RegexOption.IGNORE_CASE)

    /** An ISO-639 tag: `en`, `es-419`, `pt-br`, `zh-hans-cn`. */
    private val LANGUAGE_TAG = Regex("""^[a-z]{2,3}(-[a-z0-9]{2,8})*$""")

    /** Labels that say "no language" while looking like one. */
    private val NOT_A_LANGUAGE = setOf("und", "unknown", "none", "n/a")

    /**
     * The tag a libVLC label carries: the bracketed part when there is one, otherwise the label
     * itself with a generic `Track N` prefix stripped. A bracketed part that is a tag is taken as
     * it is, one that is a name goes through [CODES_BY_LANGUAGE_NAME], and a label that is neither
     * -- `Track 1`, `Commentary`, `[und]` -- declares no language.
     */
    private fun languageOf(label: String): String? {
        if (label.isBlank()) return null
        val bracketed = BRACKETED_PART.find(label)?.groupValues?.get(1)
        return tagOf(bracketed ?: GENERIC_TRACK_PREFIX.replaceFirst(label.trim(), ""))
    }

    private fun tagOf(text: String): String? {
        val candidate = text.trim().lowercase(Locale.ROOT)
        return when {
            candidate.isEmpty() || candidate in NOT_A_LANGUAGE -> null
            LANGUAGE_TAG.matches(candidate) -> candidate
            else -> CODES_BY_LANGUAGE_NAME[candidate]
        }
    }
}
