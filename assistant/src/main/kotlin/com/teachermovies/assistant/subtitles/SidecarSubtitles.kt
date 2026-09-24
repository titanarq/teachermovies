package com.teachermovies.assistant.subtitles

import java.io.File
import java.util.Locale

/**
 * Finds the standalone subtitle file that belongs to a media file. A sidecar `.srt`/`.ass` next to
 * the movie is the only source hidden mode can read: cues embedded in the container are not
 * reachable through the `Player` interface.
 *
 * Lookup covers the media file's own directory and a `Subs` subdirectory of it (the layout most
 * torrents ship), is case-insensitive throughout, and never throws -- a directory that does not
 * exist simply contributes no candidates.
 */
object SidecarSubtitles {
    private const val SUBS_DIRECTORY = "Subs"

    /** Supported extensions, most preferred first within a rank. */
    private val EXTENSIONS = listOf("srt", "ass")

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
        searchDirectories(mediaFile).forEachIndexed { directory, dir ->
            val entries = dir.listFiles() ?: return@forEachIndexed
            for (file in entries.sortedBy { it.name.lowercase(Locale.ROOT) }) {
                if (!file.isFile) continue
                val name = file.name.lowercase(Locale.ROOT)
                val extension = EXTENSIONS.indexOfFirst { name.endsWith(".$it") }
                if (extension < 0) continue
                val rank = rankOf(name, base, lang, EXTENSIONS[extension]) ?: continue
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

    /** The media file's own directory, then its `Subs` subdirectory if it has one. */
    private fun searchDirectories(mediaFile: File): List<File> {
        val parent = mediaFile.absoluteFile.parentFile ?: return emptyList()
        val subs =
            (parent.listFiles() ?: emptyArray())
                .firstOrNull { it.isDirectory && it.name.equals(SUBS_DIRECTORY, ignoreCase = true) }
        return listOfNotNull(parent, subs)
    }

    private data class Candidate(
        val file: File,
        val rank: Int,
        val extension: Int,
        val directory: Int,
    )
}
