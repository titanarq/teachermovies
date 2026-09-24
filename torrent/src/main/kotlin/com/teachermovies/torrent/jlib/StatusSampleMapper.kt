package com.teachermovies.torrent.jlib

import com.teachermovies.torrent.api.FilePriority
import com.teachermovies.torrent.api.TorrentFileInfo
import com.teachermovies.torrent.api.TorrentSnapshot
import com.teachermovies.torrent.policy.DownloadStateMapper
import com.teachermovies.torrent.policy.FileSelectionPolicy
import com.teachermovies.torrent.policy.RawPhase
import com.teachermovies.torrent.policy.RawStatus
import com.teachermovies.torrent.policy.etaSeconds

/**
 * One reading of a torrent handle's `torrent_status`, copied into plain values on the engine's
 * serial side so [StatusSampleMapper] can turn it into a [TorrentSnapshot] without any jlibtorrent
 * type (JVM-testable).
 *
 * [progress] is libtorrent's 0.0-1.0 fraction; [errorMessage] is null unless the torrent is in error.
 */
internal data class StatusSample(
    val phase: RawPhase,
    val paused: Boolean,
    val autoManaged: Boolean,
    val hasMetadata: Boolean,
    val isFinished: Boolean,
    val errorMessage: String?,
    val progress: Float,
    val totalWanted: Long,
    val totalWantedDone: Long,
    val downloadRate: Long,
    val uploadRate: Long,
    val numPeers: Int,
    val allTimeUpload: Long,
    val allTimeDownload: Long,
    val savePath: String?,
)

/** The pure mapping from a [StatusSample] (and libtorrent priorities) to the engine's API values. */
internal object StatusSampleMapper {
    /** libtorrent's `dont_download`, `default_priority` and `top_priority`. */
    const val LIB_SKIP = 0
    const val LIB_NORMAL = 4
    const val LIB_HIGH = 7

    /** The snapshot [current] becomes after [sample]: state, progress, rates, peers, ETA, ratio. */
    fun apply(
        current: TorrentSnapshot,
        sample: StatusSample,
    ): TorrentSnapshot {
        val state =
            DownloadStateMapper.map(
                RawStatus(
                    phase = sample.phase,
                    paused = sample.paused,
                    autoManagedQueued = sample.autoManaged,
                    hasMetadata = sample.hasMetadata,
                    hasError = sample.errorMessage != null,
                    isFinished = sample.isFinished,
                ),
            )
        val remaining = (sample.totalWanted - sample.totalWantedDone).coerceAtLeast(0)
        return current.copy(
            state = state,
            progressPercent = (sample.progress.toDouble() * 100.0).coerceIn(0.0, 100.0),
            downloadedBytes = sample.totalWantedDone,
            totalBytes = if (sample.hasMetadata) sample.totalWanted else current.totalBytes,
            downloadRateBps = sample.downloadRate,
            uploadRateBps = sample.uploadRate,
            peers = sample.numPeers,
            etaSeconds = if (sample.hasMetadata) etaSeconds(remaining, sample.downloadRate) else null,
            ratio = ratio(sample.allTimeUpload, sample.allTimeDownload),
            hasMetadata = current.hasMetadata || sample.hasMetadata,
            savePath = if (sample.hasMetadata) sample.savePath ?: current.savePath else current.savePath,
            errorMessage = sample.errorMessage,
        )
    }

    /** Uploaded over downloaded bytes; a torrent that has downloaded nothing divides by 1. */
    fun ratio(
        allTimeUpload: Long,
        allTimeDownload: Long,
    ): Double = allTimeUpload.toDouble() / allTimeDownload.coerceAtLeast(1)

    /**
     * The automatic file selection applied when metadata arrives: [FileSelectionPolicy] over [files]
     * (in torrent order), as one libtorrent priority per file for `prioritize_files`.
     */
    fun autoSelection(files: List<TorrentFileInfo>): AutoSelection {
        val selection = FileSelectionPolicy.select(files)
        val priorities =
            IntArray(files.size) { index -> libPriorityOf(selection.priorities[index] ?: FilePriority.Normal) }
        return AutoSelection(priorities, selection.mainFileIndex)
    }

    /** Skip/Normal/High -> libtorrent file priority 0/4/7. */
    fun libPriorityOf(priority: FilePriority): Int =
        when (priority) {
            FilePriority.Skip -> LIB_SKIP
            FilePriority.Normal -> LIB_NORMAL
            FilePriority.High -> LIB_HIGH
        }

    /** A libtorrent file priority (0-7) back to the API: 0 is Skip, 1-5 Normal, 6-7 High. */
    fun filePriorityOf(libPriority: Int): FilePriority =
        when {
            libPriority <= LIB_SKIP -> FilePriority.Skip
            libPriority >= 6 -> FilePriority.High
            else -> FilePriority.Normal
        }
}

/** [StatusSampleMapper.autoSelection]'s result: [libPriorities] indexed by file, and the main file. */
internal class AutoSelection(
    val libPriorities: IntArray,
    val mainFileIndex: Int?,
)
