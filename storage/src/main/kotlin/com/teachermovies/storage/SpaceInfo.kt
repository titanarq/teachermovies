package com.teachermovies.storage

/**
 * How much room the volume a storage root lives on has.
 *
 * [freeBytes] is what an unprivileged app may actually write, not what the filesystem reports as
 * unallocated; both are 0 for a root that does not exist.
 */
data class SpaceInfo(val freeBytes: Long, val totalBytes: Long)
