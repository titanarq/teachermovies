package com.teachermovies.storage

import java.io.File

/** [SpaceProvider] over the real filesystem, through `File.usableSpace` and `File.totalSpace`. */
class FileSpaceProvider : SpaceProvider {
    override fun spaceOf(root: File): SpaceInfo = SpaceInfo(freeBytes = root.usableSpace, totalBytes = root.totalSpace)
}
