package com.teachermovies.assistant.subtitles

import java.io.File
import java.util.Locale

/**
 * Finds the standalone subtitle file a movie shipped with, next to it or in its `Subs` directory:
 * hidden mode's first source, ahead of an embedded track ([EmbeddedSubtitleTracks]) and of the file
 * the laptop bridge downloaded ([DownloadedSubtitles], #284).
 *
 * A download is deliberately not a candidate here, whatever its language: it is the last resort
 * rather than the first, and the Spanish one the bridge fetches for every movie must never be
 * readable as the English track.
 *
 * Lookup covers the media file's own directory and a `Subs` subdirectory of it (the layout most
 * torrents ship), is case-insensitive throughout, and never throws -- a directory that does not
 * exist simply contributes no candidates.
 */
object SidecarSubtitles {
    /**
     * Returns the best sidecar subtitle file for [mediaFile] in [language], or `null` when none
     * matches.
     *
     * Candidates are ranked by name pattern first -- `<base>.<language>.<ext>`, then
     * `<base>.<ext>`, then any `*.<language>.<ext>` -- then by extension (`.srt` before `.ass`),
     * then by directory (the media file's own before `Subs`), then by name, so the answer is
     * deterministic whatever order the filesystem lists files in.
     */
    fun findFor(
        mediaFile: File,
        language: String = "en",
    ): File? {
        val base = mediaFile.nameWithoutExtension.lowercase(Locale.ROOT)
        val lang = language.lowercase(Locale.ROOT)

        val candidates = mutableListOf<Candidate>()
        SubtitleFileSearch.directories(mediaFile).forEachIndexed { directory, dir ->
            val entries = dir.listFiles() ?: return@forEachIndexed
            for (file in entries.sortedBy { it.name.lowercase(Locale.ROOT) }) {
                if (!file.isFile) continue
                val name = file.name.lowercase(Locale.ROOT)
                if (DownloadedSubtitles.isDownloadedName(name)) continue
                val extension = SubtitleFileSearch.EXTENSIONS.indexOfFirst { name.endsWith(".$it") }
                if (extension < 0) continue
                val rank = rankOf(name, base, lang, SubtitleFileSearch.EXTENSIONS[extension]) ?: continue
                candidates += Candidate(file, rank, extension, directory)
            }
        }

        return candidates
            .minWithOrNull(compareBy({ it.rank }, { it.extension }, { it.directory }))
            ?.file
    }

    private fun rankOf(
        name: String,
        base: String,
        lang: String,
        ext: String,
    ): Int? =
        when {
            name == "$base.$lang.$ext" -> 0
            name == "$base.$ext" -> 1
            name.endsWith(".$lang.$ext") -> 2
            else -> null
        }

    private data class Candidate(
        val file: File,
        val rank: Int,
        val extension: Int,
        val directory: Int,
    )
}
