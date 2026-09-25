package com.teachermovies.torrent.jlib

import com.teachermovies.core.model.TorrentId

/**
 * Which session events ask for an immediate resume-data save of their torrent, on top of the
 * periodic save every 60 s. Pure: plain values in, the torrent to save (or null) out.
 *
 * A torrent that finishes is saved at once (#246): otherwise a power cut within a minute of
 * completing restores it from the last periodic save, as `downloading` at an older progress, and
 * the tail of the file is downloaded again.
 */
internal object ResumeSaveTriggers {
    /** The torrent whose resume data [event] asks to save now, or null; [known] are the live ids. */
    fun torrentToSave(
        event: SessionEvent,
        known: Set<TorrentId>,
    ): TorrentId? =
        when (event) {
            // A late alert for a removed torrent must not bring its resume entry back.
            is SessionEvent.Finished -> event.id?.takeIf { it in known }

            else -> null
        }
}
