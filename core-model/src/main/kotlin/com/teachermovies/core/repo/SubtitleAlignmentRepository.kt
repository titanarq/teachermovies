package com.teachermovies.core.repo

import com.teachermovies.core.model.SubtitleAlignment
import com.teachermovies.core.model.TorrentId

/**
 * Domain-level access to the stored subtitle alignments (#274, ADR-0005 §6; #283 computes them and
 * looks one up when the panel shows the Spanish line). One row per movie and Spanish file: a film
 * with two candidate files keeps two alignments, and [bestFor] picks the better-scored one.
 *
 * Deciding how good a score has to be before the aligned line wins over a machine translation is the
 * caller's (#283), not this repository's.
 */
interface SubtitleAlignmentRepository {
    suspend fun get(
        id: TorrentId,
        subtitlePath: String,
    ): SubtitleAlignment?

    /** The best-scored alignment stored for [id], or null when it has none. */
    suspend fun bestFor(id: TorrentId): SubtitleAlignment?

    /** Inserts [alignment], or replaces the row for the same file of the same movie. */
    suspend fun save(alignment: SubtitleAlignment)

    suspend fun delete(
        id: TorrentId,
        subtitlePath: String,
    )

    /** Every alignment of one movie; called when the torrent itself goes. */
    suspend fun deleteForTorrent(id: TorrentId)
}
