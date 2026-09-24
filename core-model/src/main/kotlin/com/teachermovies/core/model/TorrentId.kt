package com.teachermovies.core.model

private val INFO_HASH_PATTERN = Regex("^[0-9a-f]{40}$|^[0-9a-f]{64}$")

/**
 * A BitTorrent info-hash: 40 lower-case hex characters for a v1 torrent, 64 for a v2 torrent.
 *
 * Every module keys torrents by this, and it becomes the download directory name, so a value that
 * is not exactly a lower-case hex info-hash is rejected here instead of at each use site.
 */
@JvmInline
value class TorrentId(val value: String) {
    init {
        require(value.matches(INFO_HASH_PATTERN)) {
            "info-hash must be 40 or 64 lower-case hex characters, was '$value'"
        }
    }
}
