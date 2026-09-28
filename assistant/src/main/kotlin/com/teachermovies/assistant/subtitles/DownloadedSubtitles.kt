package com.teachermovies.assistant.subtitles

import java.io.File
import java.util.Locale

/**
 * Finds the subtitle file the laptop bridge downloaded from OpenSubtitles for a movie (ADR-0005 §5):
 * `<base>.<lang>.opensubtitles.srt` in the movie's own `subs/` directory, [MARKER] being what tells
 * an automatic download apart from a file the movie shipped with and from one uploaded by hand.
 *
 * Hidden mode's last resort for the English track (#284), asked only once the movie has neither a
 * sidecar ([SidecarSubtitles], which ignores these names) nor an embedded track for the language.
 * Only a name whose language part is [language] is ever a candidate, so the Spanish download the
 * bridge fetches for every movie cannot be mistaken for the English one.
 *
 * The search covers the same directories as [SidecarSubtitles], is case-insensitive throughout and
 * never throws -- a directory that does not exist simply contributes no candidates.
 */
object DownloadedSubtitles {
    /**
     * The file-name marker an automatically downloaded subtitle carries. `:bridge-protocol`'s
     * `BridgeSubtitleProtocol.fileName` spells the name it appears in: `<base>.<lang>.<marker>.<ext>`;
     * a feature module does not depend on another one, so the spelling is repeated here.
     */
    const val MARKER: String = "opensubtitles"

    /** Whether [name] -- lower-cased -- carries [MARKER], and so is an automatic download's. */
    fun isDownloadedName(name: String): Boolean = name.contains(".$MARKER.")

    /**
     * Returns the subtitle file downloaded for [mediaFile] in [language], or `null` when there is
     * none.
     *
     * Candidates are ranked by name pattern first -- the bridge's own name for this movie,
     * `<base>.<language>.<marker>.<ext>`, then the same pattern on any other base, which is what a
     * download made for an earlier main file leaves behind -- then by extension (`.srt` before
     * `.ass`), then by directory (the media file's own before `Subs`), then by name, so the answer is
     * deterministic whatever order the filesystem lists files in. A file whose language part is
     * another language (`movie.es.opensubtitles.srt` for `"en"`) is no candidate at all.
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
                if (!isDownloadedName(name)) continue
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
            name == "$base.$lang.$MARKER.$ext" -> 0
            name.endsWith(".$lang.$MARKER.$ext") -> 1
            else -> null
        }

    private data class Candidate(
        val file: File,
        val rank: Int,
        val extension: Int,
        val directory: Int,
    )
}
