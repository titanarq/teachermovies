package com.teachermovies.torrent.policy

/**
 * The no-seeding rule: once a torrent has finished downloading the app must not keep sharing it,
 * even for lawful content. Uploading while downloading is inherent to BitTorrent and is untouched.
 * A paused finished torrent drops all its peers and announces `stopped` to the tracker.
 */
object SeedingPolicy {
    /** True when a finished torrent is still running and so must be paused. */
    fun shouldStopSeeding(
        isFinished: Boolean,
        paused: Boolean,
    ): Boolean = isFinished && !paused
}
