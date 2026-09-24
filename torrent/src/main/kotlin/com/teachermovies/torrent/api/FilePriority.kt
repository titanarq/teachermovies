package com.teachermovies.torrent.api

/**
 * How eagerly the engine should fetch one file inside a torrent.
 *
 * [Skip] downloads none of the file's pieces (samples, extras, artwork); [Normal] is the default
 * download order; [High] moves the file's pieces ahead of other [Normal] files, which is how a
 * movie file and its subtitle track are prioritised over anything else in the torrent.
 */
enum class FilePriority {
    Skip,
    Normal,
    High,
}
