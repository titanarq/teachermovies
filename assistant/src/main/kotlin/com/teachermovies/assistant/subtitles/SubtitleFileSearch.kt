package com.teachermovies.assistant.subtitles

import java.io.File

/**
 * What the two subtitle-file lookups of hidden mode share: the extensions this module can parse and
 * the directories a movie keeps its subtitle files in ([SidecarSubtitles], [DownloadedSubtitles]).
 */
internal object SubtitleFileSearch {
    /** Supported extensions, most preferred first within a rank. */
    val EXTENSIONS = listOf("srt", "ass")

    private const val SUBS_DIRECTORY = "Subs"

    /**
     * The media file's own directory, then its `Subs` subdirectory if it has one -- the layout most
     * torrents ship, and where a subtitle the laptop bridge downloaded lands (ADR-0005 §5). A
     * directory that does not exist contributes nothing instead of throwing.
     */
    fun directories(mediaFile: File): List<File> {
        val parent = mediaFile.absoluteFile.parentFile ?: return emptyList()
        val subs =
            (parent.listFiles() ?: emptyArray())
                .firstOrNull { it.isDirectory && it.name.equals(SUBS_DIRECTORY, ignoreCase = true) }
        return listOfNotNull(parent, subs)
    }
}
