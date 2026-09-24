package com.teachermovies.storage

import java.io.File

/**
 * Answers how much space a storage root has, so callers decide whether a download fits before
 * starting it instead of failing halfway through.
 *
 * An interface because the volume behind [root] may be a removed USB stick: implementations report
 * that as zeros rather than throwing, and tests substitute fixed numbers.
 */
interface SpaceProvider {
    fun spaceOf(root: File): SpaceInfo
}
