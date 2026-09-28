package com.teachermovies.bridge.protocol

import kotlinx.serialization.Serializable

/**
 * One movie the TV still wants a subtitle for, in one [language] (#280, ADR-0005 §5): an element of
 * the body of `GET /api/bridge/subtitle-needs`. Only needs a search may act on now are listed, so a
 * movie already served, one a bridge is searching for, and one waiting out its seven-day not-found
 * retry never appear.
 *
 * [movieHash] is the OpenSubtitles moviehash (#279) the bridge searches by, or null while the TV has
 * not computed it; [title] is what it falls back to searching by. [state] is where the search last
 * stood -- `pending` (never searched), `not_found` (the last search came up empty and its retry is
 * due) or `failed` (the last search errored) -- and [attempts] how many the TV has already handed
 * out. Carries no file system path: where the file lands is the TV's choice
 * ([BridgeSubtitleProtocol.fileName]), never the bridge's.
 */
@Serializable
data class SubtitleNeedDto(
    val torrentId: String,
    val title: String,
    val language: String,
    val movieHash: String?,
    val state: String,
    val attempts: Int,
)

/**
 * `data` of an `event: subtitles-needed` frame on `GET /api/bridge/jobs` (#280): a nudge, not a
 * payload -- the bridge answers it by reading `GET /api/bridge/subtitle-needs` again. [torrentId]
 * names the movie that changed when the TV knows it (a download just completed; the phone asked for
 * a retry now, #285) and is null when only "the list may have changed" is meant. Nothing is queued
 * for a bridge that is not connected: it reads the list when it connects (#282).
 */
@Serializable
data class SubtitlesNeededDto(
    val torrentId: String?,
)

/**
 * Body of `POST /api/bridge/subtitle-status` (#280): what became of one search taken from the needs
 * list. [status] is `searching` (picked up, so the TV stops handing it out), `not_found`
 * (OpenSubtitles had nothing; the TV waits seven days before listing it again) or `failed`
 * (credentials, quota, network; listed again on the next pass), with optional [message] detail --
 * never a secret, and never an OpenSubtitles credential.
 */
@Serializable
data class SubtitleStatusDto(
    val torrentId: String,
    val language: String,
    val status: String,
    val message: String? = null,
)

/** Body of the 201 answer to `POST /api/bridge/subtitles`: where the TV stored the file. */
@Serializable
data class SubtitleUploadedDto(
    val path: String,
)

/** Names and limits of the subtitle routes, shared by the TV and the bridge (#280). */
object BridgeSubtitleProtocol {
    /** SSE event name of a frame carrying a [SubtitlesNeededDto], on the job stream of #275. */
    const val SUBTITLES_NEEDED_EVENT: String = "subtitles-needed"

    /** Largest subtitle file `POST /api/bridge/subtitles` accepts; over it is 413 `too_large`. */
    const val MAX_SUBTITLE_BYTES: Long = 2L * 1024 * 1024

    /** Largest `POST /api/bridge/subtitle-status` body; over it is 400 `too_large`. */
    const val MAX_STATUS_BYTES: Int = 4 * 1024

    /** Multipart fields of `POST /api/bridge/subtitles`; [VARIANT_FIELD] is the only optional one. */
    const val TORRENT_ID_FIELD: String = "torrentId"
    const val LANGUAGE_FIELD: String = "language"
    const val FILE_FIELD: String = "file"
    const val VARIANT_FIELD: String = "variant"

    /** Values of [SubtitleStatusDto.status]. */
    const val STATUS_SEARCHING: String = "searching"
    const val STATUS_NOT_FOUND: String = "not_found"
    const val STATUS_FAILED: String = "failed"

    /**
     * The marker an automatically downloaded subtitle carries in its file name, which is what keeps
     * it distinguishable from a hand-uploaded one and from the hidden English track (#284).
     */
    const val OPENSUBTITLES_MARKER: String = "opensubtitles"

    /**
     * The file name a downloaded subtitle gets, inside the movie's own `subs/` directory
     * (ADR-0005 §5): `<base>.<lang>.opensubtitles.srt`, where `<base>` is the movie file's name
     * without its extension. The TV picks it from the movie it is for, so a name arriving off the
     * network never chooses where anything lands.
     */
    fun fileName(
        base: String,
        language: String,
    ): String = "$base.$language.$OPENSUBTITLES_MARKER.srt"
}
