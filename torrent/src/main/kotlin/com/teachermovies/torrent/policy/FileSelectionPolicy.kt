package com.teachermovies.torrent.policy

import com.teachermovies.torrent.api.FilePriority
import com.teachermovies.torrent.api.TorrentFileInfo

/**
 * The engine's decision on which of a torrent's files to fetch, so a fresh download starts
 * pulling only the movie and its subtitles instead of every file the torrent carries.
 *
 * [priorities] maps every [TorrentFileInfo.index] from the input list to the [FilePriority] the
 * engine should apply; [mainFileIndex] is the chosen movie file, or `null` when the torrent has
 * no video file at all.
 */
data class Selection(
    val priorities: Map<Int, FilePriority>,
    val mainFileIndex: Int?,
)

/**
 * Chooses which files of a torrent to download, so [com.teachermovies.torrent.api.TorrentEngine]
 * (via the jlibtorrent adapter, #52) can set file priorities the moment metadata arrives: the
 * movie file and its subtitles at [FilePriority.Normal], everything else -- samples, extras,
 * artwork, other videos -- at [FilePriority.Skip].
 *
 * Pure Kotlin, no jlibtorrent type in sight (ADR-0001 §4): it only ever sees [TorrentFileInfo].
 */
object FileSelectionPolicy {
    private val videoExtensions =
        setOf("mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "webm", "wmv")

    private val subtitleExtensions = setOf("srt", "ass", "ssa", "vtt", "sub", "idx")

    /**
     * Matches a path segment (a folder name or the file name itself) that marks a video as
     * *not* the main feature: a sample clip, a trailer, or bonus/extra material. Checked against
     * each segment individually, case-insensitively, so `Movie/Extras/x.mkv` is excluded by its
     * `Extras` folder even though the file name itself looks innocuous.
     */
    private val excludedSegment =
        Regex(
            "sample|trailer|extras?|featurettes?|behind.the.scenes|deleted.scenes|bonus",
            RegexOption.IGNORE_CASE,
        )

    /** Chooses priorities and the main file for [files], the torrent's full file list. */
    fun select(files: List<TorrentFileInfo>): Selection {
        val videos = files.filter { it.extension() in videoExtensions }

        // No video at all: nothing to skip, there is no main file either.
        if (videos.isEmpty()) {
            return Selection(priorities = files.associate { it.index to FilePriority.Normal }, mainFileIndex = null)
        }

        val mainFile = videos.pickMainFile()

        val priorities =
            files.associate { file ->
                val priority =
                    when {
                        file.index == mainFile.index -> FilePriority.Normal
                        file.extension() in subtitleExtensions -> FilePriority.Normal
                        else -> FilePriority.Skip
                    }
                file.index to priority
            }

        return Selection(priorities = priorities, mainFileIndex = mainFile.index)
    }

    /**
     * The largest video that isn't a sample/trailer/extra, or -- when every video in the
     * torrent matches that pattern -- simply the largest video. Only called with a non-empty
     * list of videos, so a result always exists.
     */
    private fun List<TorrentFileInfo>.pickMainFile(): TorrentFileInfo {
        val nonExtras = filterNot { it.isExcluded() }
        return (nonExtras.ifEmpty { this }).maxBy { it.sizeBytes }
    }

    private fun TorrentFileInfo.isExcluded(): Boolean =
        path.split('/', '\\').any { segment -> excludedSegment.containsMatchIn(segment) }

    private fun TorrentFileInfo.extension(): String = path.substringAfterLast('.', "").lowercase()
}
