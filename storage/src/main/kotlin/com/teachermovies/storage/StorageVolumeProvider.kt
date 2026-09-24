package com.teachermovies.storage

/**
 * Lists the storage volumes the app can currently download to.
 *
 * An interface so `VolumeSelector` and its callers do not depend on `Context`/`StorageManager`:
 * tests substitute a fixed list, the production implementation is `AndroidStorageVolumeProvider`.
 */
interface StorageVolumeProvider {
    fun volumes(): List<VolumeInfo>
}
