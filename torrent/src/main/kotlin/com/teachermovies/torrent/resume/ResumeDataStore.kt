package com.teachermovies.torrent.resume

import com.teachermovies.core.model.TorrentId

/**
 * Where the engine keeps each torrent's libtorrent resume data (the bencoded `.fastresume` blob),
 * so every torrent reappears in its previous state after an app restart or a reboot.
 *
 * The store treats the bytes as opaque apart from a structural sanity check; decoding them is the
 * engine's job. Implementations are called from the engine's serial dispatcher only.
 */
interface ResumeDataStore {
    /** Stores [bytes] as the resume data of torrent [id], replacing any previous copy. */
    fun save(
        id: TorrentId,
        bytes: ByteArray,
    )

    /** Every stored torrent's resume data; entries that cannot be read are left out. */
    fun loadAll(): Map<TorrentId, ByteArray> = load().entries

    /** Like [loadAll], but also reports every entry that was skipped and why. Never throws. */
    fun load(): ResumeDataLoad

    /** Forgets torrent [id]'s resume data; a no-op when there is none. */
    fun delete(id: TorrentId)
}

/** [ResumeDataStore.load]'s result: the readable [entries] and the [skipped] ones. */
data class ResumeDataLoad(
    val entries: Map<TorrentId, ByteArray>,
    val skipped: List<SkippedResumeData>,
)

/** A stored entry [ResumeDataStore.load] could not use: its [name] (a file name) and a [reason]. */
data class SkippedResumeData(
    val name: String,
    val reason: String,
)
