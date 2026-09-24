package com.teachermovies.torrent.api

import com.teachermovies.core.model.TorrentId

/** Why a [TorrentEngine] call failed, carried inside [EngineResult.Failure]. */
sealed interface EngineError {
    /** The magnet URI passed to [TorrentEngine.addMagnet] could not be parsed. */
    data object InvalidMagnet : EngineError

    /** The bytes passed to [TorrentEngine.addTorrentFile] are not a valid .torrent file. */
    data object InvalidTorrentFile : EngineError

    /** No torrent with the given [TorrentId] is known to the engine. */
    data object UnknownTorrent : EngineError

    /** A torrent with this [id] is already known to the engine. */
    data class AlreadyExists(
        val id: TorrentId,
    ) : EngineError

    /** The call needs metadata or a running engine that is not there yet. */
    data object NotReady : EngineError

    /** The engine understood the call but does not implement it yet. */
    data object Unsupported : EngineError

    /** An I/O failure the engine could not recover from; [message] is its own text. */
    data class Io(
        val message: String,
    ) : EngineError
}
