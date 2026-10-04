package com.teachermovies.player.vlc

import com.teachermovies.player.api.Track
import java.io.File
import java.util.Locale

/**
 * Ties the subtitle tracks libVLC lists to the files added as slaves, which libVLC itself does not
 * do: it labels a slave by its own guess (both `subs/X.en.opensubtitles.srt` and
 * `subs/X.es.opensubtitles.srt` came out as `English`). This object touches no `org.videolan` type,
 * so it is unit tested on the JVM.
 *
 * libVLC 3 creates the container's subtitle ES when it opens the file and a slave's ES afterwards,
 * in the order the slaves were added; so the external tracks are the last ones of the list, one per
 * slave, paired in addition order. While only some slaves are published the tail is shorter, and
 * the pairing follows the files added first.
 */
internal object ExternalSubtitleTracks {
    /** `<base>.<lang>.<ext>` and `<base>.<lang>.opensubtitles.<ext>`. */
    private val FILE_LANGUAGE =
        Regex("""^.+\.([a-z]{2,3}(?:-[a-z0-9]{2,8})*)\.(opensubtitles\.)?(srt|ass|ssa|vtt)$""", RegexOption.IGNORE_CASE)

    /** Three-letter codes accepted from a plain `<base>.<lang>.srt` name, where `The.Big.srt` must not read as a language. */
    private val THREE_LETTER =
        setOf("eng", "spa", "fre", "fra", "ger", "deu", "ita", "por", "rus", "jpn", "chi", "zho", "kor")

    /** The language [file]'s name declares (`X.en.opensubtitles.srt`, `X.es.srt`), null when it declares none. */
    fun languageOf(file: File): String? {
        val match = FILE_LANGUAGE.matchEntire(file.name) ?: return null
        val tag = match.groupValues[1].lowercase(Locale.ROOT)
        val downloaded = match.groupValues[2].isNotEmpty()
        return tag.takeIf { downloaded || tag.length == 2 || tag in THREE_LETTER }
    }

    /** The name shown for [file]'s track: `OpenSubtitles (en)` for a download, [fallback] otherwise. */
    fun nameOf(
        file: File,
        fallback: String,
    ): String {
        val lower = file.name.lowercase(Locale.ROOT)
        if (".opensubtitles." !in lower) return fallback
        val language = languageOf(file)
        return if (language == null) "OpenSubtitles" else "OpenSubtitles ($language)"
    }

    /** [Track] for [file] with the id [id] the player gave it. */
    fun trackOf(
        id: String,
        file: File,
        libVlcName: String,
        libVlcLanguage: String?,
    ): Track =
        Track(
            id = id,
            name = nameOf(file, libVlcName),
            language = languageOf(file) ?: libVlcLanguage,
            external = true,
        )

    /** [tracks] with the last `min(files, tracks)` ones marked external and named after [files]. */
    fun mark(
        tracks: List<Track>,
        files: List<File>,
    ): List<Track> {
        val count = minOf(files.size, tracks.size)
        val firstExternal = tracks.size - count
        return tracks.mapIndexed { index, track ->
            if (index < firstExternal) {
                track
            } else {
                trackOf(track.id, files[index - firstExternal], track.name, track.language)
            }
        }
    }
}
