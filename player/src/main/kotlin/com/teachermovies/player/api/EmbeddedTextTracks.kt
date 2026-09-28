package com.teachermovies.player.api

import com.teachermovies.player.mkv.MatroskaSubtitles
import com.teachermovies.player.mkv.MkvTracksResult
import com.teachermovies.player.mp4.Mp4Subtitles
import com.teachermovies.player.mp4.Mp4TracksResult
import java.io.File

/**
 * The subtitle languages a media file already carries, read from the container's own headers: a
 * header-only probe for the subtitle-needs decision of #280 (ADR-0005 §5), which has to know
 * whether a finished movie still needs the bridge to look for a language it already has.
 *
 * It is the metadata half of [Player.extractTextSubtitle] and uses the same pure-Kotlin readers --
 * `MatroskaSubtitles` for an EBML header, `Mp4Subtitles` for a leading `ftyp` box, each deciding
 * from the file's first bytes rather than from its name -- so it needs no libVLC, no playback and
 * no Android type, and it never touches the media itself: only headers are read, and every video
 * and audio payload is seeked past. Blocking file I/O, so the caller picks the dispatcher.
 *
 * It never throws. A missing file, an unreadable one, a truncated or corrupt one, a container
 * other than Matroska/WebM and MP4/M4V/MOV (AVI, MPEG-TS, ...) and a container that declares no
 * subtitle track at all all answer an empty list -- the safe answer for #280, which is then to let
 * the bridge look for the language and find nothing to do.
 */
object EmbeddedTextTracks {
    /**
     * The languages [file] declares for its subtitle tracks, in container order and one entry per
     * track, so a movie with two English tracks answers `eng` twice.
     *
     * A Matroska track reports its `LanguageIETF` (BCP 47, `en-US`) when it has one and its
     * `Language` (ISO 639-2, `eng`) otherwise -- the precedence Matroska itself defines -- and an
     * MP4 track reports its `mdhd` language (ISO 639-2/T). A track that declares no language
     * reports its container's default: `eng` for Matroska, `und` for an MP4 `mdhd` left unset or
     * holding a QuickTime code.
     *
     * Only subtitle tracks the assistant can read as text are listed: a Matroska track whose codec
     * id is a text codec (`S_TEXT/UTF8`, `S_TEXT/ASS`, `S_TEXT/SSA`, `S_TEXT/WEBVTT`,
     * `D_WEBVTT/SUBTITLES`, `D_WEBVTT/CAPTIONS`) and an MP4 track whose sample entry is `tx3g`
     * (mov_text). Image-based tracks (PGS, VobSub, DVB) and any other codec are skipped, so a movie
     * whose only English track is a picture answers without `eng`, exactly as if it declared no
     * English subtitle at all -- which is what #280 needs to know to ask the bridge for one.
     */
    fun languagesOf(file: File): List<String> =
        when (val matroska = MatroskaSubtitles.textTracks(file)) {
            is MkvTracksResult.Tracks -> {
                matroska.tracks
                    .filter { it.codecId in MatroskaSubtitles.TEXT_CODEC_IDS }
                    .map { it.languageIetf ?: it.language }
            }

            MkvTracksResult.NotMatroska -> {
                mp4Languages(file)
            }

            is MkvTracksResult.Failed -> {
                emptyList()
            }
        }

    /** The other reader, for a file whose first bytes are not the EBML magic. */
    private fun mp4Languages(file: File): List<String> =
        when (val mp4 = Mp4Subtitles.textTracks(file)) {
            is Mp4TracksResult.Tracks -> mp4.tracks.filter { it.sampleEntry == Mp4Subtitles.TX3G }.map { it.language }
            Mp4TracksResult.NotMp4, is Mp4TracksResult.Failed -> emptyList()
        }
}
