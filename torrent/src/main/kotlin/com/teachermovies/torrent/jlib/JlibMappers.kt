package com.teachermovies.torrent.jlib

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.TorrentId
import com.teachermovies.torrent.api.TorrentSnapshot

/**
 * The pure half of [JLibTorrentEngine]: every decision that does not need a live session, written
 * against plain values so it runs in JVM tests without loading jlibtorrent's native library. No
 * jlibtorrent type appears in any signature here.
 */
internal object JlibMappers {
    /** The BitTorrent port the session listens on, over IPv4 and IPv6. */
    const val LISTEN_PORT = 6881

    /** libtorrent's `listen_interfaces` value: every IPv4 and IPv6 interface on [port]. */
    fun listenInterfaces(port: Int = LISTEN_PORT): String = "0.0.0.0:$port,[::]:$port"

    /**
     * The [TorrentId] of a torrent from its hex info-hashes: the v1 (SHA-1) hash when there is one,
     * which is what a `btih` magnet names, otherwise the v2 (SHA-256) hash. libtorrent reports an
     * absent hash as all zeros, so an all-zero or malformed value counts as absent; null when neither
     * is usable.
     */
    fun torrentIdOf(
        v1Hex: String?,
        v2Hex: String?,
    ): TorrentId? = usableHash(v1Hex, 40) ?: usableHash(v2Hex, 64)

    /**
     * The snapshot of a torrent the engine has just handed to the session, before any alert about it.
     * A magnet has no metadata yet ([hasMetadata] false, [DownloadState.FetchingMetadata]); a
     * `.torrent` file already carries it ([DownloadState.Queued] until the session reports progress,
     * with its [name], [totalBytes] and [savePath]).
     */
    fun addedSnapshot(
        id: TorrentId,
        name: String?,
        hasMetadata: Boolean,
        totalBytes: Long,
        savePath: String?,
    ): TorrentSnapshot =
        TorrentSnapshot(
            id = id,
            name = name?.takeIf { it.isNotBlank() } ?: id.value,
            state = if (hasMetadata) DownloadState.Queued else DownloadState.FetchingMetadata,
            progressPercent = 0.0,
            downloadedBytes = 0,
            totalBytes = if (hasMetadata) totalBytes else 0,
            downloadRateBps = 0,
            uploadRateBps = 0,
            peers = 0,
            etaSeconds = null,
            ratio = 0.0,
            hasMetadata = hasMetadata,
            savePath = if (hasMetadata) savePath else null,
            errorMessage = null,
        )

    /**
     * Applies a `metadata_received_alert`: the torrent now has its [name], [totalBytes] and
     * [savePath] and starts [DownloadState.Downloading]. A blank [name] keeps the current one.
     */
    fun withMetadata(
        snapshot: TorrentSnapshot,
        name: String?,
        totalBytes: Long,
        savePath: String?,
    ): TorrentSnapshot =
        snapshot.copy(
            name = name?.takeIf { it.isNotBlank() } ?: snapshot.name,
            state = DownloadState.Downloading,
            totalBytes = totalBytes,
            hasMetadata = true,
            savePath = savePath ?: snapshot.savePath,
        )

    /** Applies a failed `add_torrent_alert`: the session refused the torrent with [message]. */
    fun withAddError(
        snapshot: TorrentSnapshot,
        message: String,
    ): TorrentSnapshot = snapshot.copy(state = DownloadState.Error, errorMessage = message)

    private fun usableHash(
        hex: String?,
        length: Int,
    ): TorrentId? {
        val lower = hex?.lowercase() ?: return null
        if (lower.length != length || lower.any { it !in HEX_DIGITS } || lower.all { it == '0' }) return null
        return TorrentId(lower)
    }

    private const val HEX_DIGITS = "0123456789abcdef"
}
