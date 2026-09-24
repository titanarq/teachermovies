package com.teachermovies.player.mkv

import com.teachermovies.player.api.Track

/**
 * Maps a subtitle [Track.id] libVLC published to the Matroska `TrackNumber` it plays.
 *
 * libVLC 3's MKV demuxer creates one elementary stream per `TrackEntry` when it opens the file,
 * using the `TrackNumber` as the ES id, and a subtitle added with `addExternalSubtitle` is a slave
 * input whose stream only appears afterwards. So the first `mkvTracks.size` entries of libVLC's
 * subtitle list are the embedded tracks, in file order, and anything after them is external.
 */
internal object MkvTrackMapping {
    /**
     * The `TrackNumber` behind [trackId], or null when it is not an embedded track: unknown to
     * libVLC, or one of the external tracks listed after the embedded ones. A numeric [trackId]
     * equal to an embedded `TrackNumber` wins; otherwise the n-th embedded subtitle track in
     * libVLC's order maps to the n-th Matroska subtitle track.
     */
    fun resolve(
        trackId: String,
        vlcSubtitleTracks: List<Track>,
        mkvTracks: List<MkvTextTrack>,
    ): Long? = resolveNumber(trackId, vlcSubtitleTracks, mkvTracks.map { it.trackNumber })

    /**
     * The same rule over plain track numbers, in file order. libVLC 3's MP4 demuxer also uses the
     * `tkhd` `track_ID` as the ES id and creates its ES in `trak` order, so `Mp4Subtitles`' tracks
     * resolve through here too.
     */
    fun resolveNumber(
        trackId: String,
        vlcSubtitleTracks: List<Track>,
        embeddedNumbers: List<Long>,
    ): Long? {
        val embedded = vlcSubtitleTracks.take(embeddedNumbers.size)
        val index = embedded.indexOfFirst { it.id == trackId }
        if (index < 0) return null
        val byNumber = trackId.toLongOrNull()?.takeIf { it in embeddedNumbers }
        return byNumber ?: embeddedNumbers[index]
    }
}
