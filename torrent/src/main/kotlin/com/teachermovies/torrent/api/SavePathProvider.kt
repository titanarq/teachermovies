package com.teachermovies.torrent.api

import com.teachermovies.core.model.TorrentId
import java.io.File

/**
 * Where the engine stores the payload of one torrent: one directory per torrent, following the
 * `<volume>/Movies/<torrent-id>/` layout (AGENTS.md "Persistence"). `:app-tv` wires this to the
 * storage module, so `:torrent` never depends on `:storage` directly.
 */
fun interface SavePathProvider {
    fun savePathFor(id: TorrentId): File
}
