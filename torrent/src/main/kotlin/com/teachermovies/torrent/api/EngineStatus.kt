package com.teachermovies.torrent.api

/** Lifecycle of the engine itself, as opposed to any one torrent's `DownloadState`. */
enum class EngineStatus {
    Stopped,
    Starting,
    Running,
    Error,
}
