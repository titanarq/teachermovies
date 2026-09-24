# Module: storage

**Gradle path:** `:storage`.

## Responsibility
- Enumerate storage volumes (internal, USB, SSD) via `StorageManager`; pick and persist the download volume.
- Layout `<volume>/Movies/<torrent-id>/...`; free/total space; detect removed volumes.
- `DownloadLayout(volumeRoot)` is the one place that turns an id into a path: `moviesDir()`,
  `torrentDir(id)`, `ensureTorrentDir(id): Result<File>` and `resolveInTorrent(id, relativePath)`.
  An id is a directory name and never a path -- anything outside `[0-9A-Za-z_-]` is rejected --
  `ensureTorrentDir` reports a failure instead of throwing, and `resolveInTorrent` rejects a
  relative path that still leaves the torrent directory once `..` segments are collapsed, so a name
  arriving off the network never chooses where it lands on disk.
- Free/total space goes through `SpaceProvider.spaceOf(root): SpaceInfo(freeBytes, totalBytes)`,
  with `FileSpaceProvider` (`File.usableSpace` / `File.totalSpace`) as the production
  implementation. A root that is gone reports zeros rather than throwing.
- `VolumeInfo(id, label, root, removable, primary, mounted)` describes one volume; `id` is the
  volume's uuid or `"primary"`, `root` is this app's own directory on it. `StorageVolumeProvider`
  lists the volumes currently available; `AndroidStorageVolumeProvider(context)` is the production
  implementation, over `Context.getExternalFilesDirs(null)` matched with
  `StorageManager.getStorageVolume(File)` -- app-specific directories only, so no
  `MANAGE_EXTERNAL_STORAGE` and no storage permission at all.
- `VolumeSelector.select(volumes, persistedId, spaceProvider): VolumeSelection` turns the persisted
  `downloadVolumeId` (`SettingsRepository`, `:core-model`) and the current volume list into one of
  `Selected`, `PersistedMissing(persistedId, fallback)` (persisted volume absent or unmounted) or
  `NoneAvailable` (no volumes at all). The fallback rule -- used both with no persisted id and as
  `PersistedMissing.fallback` -- is the largest-free mounted removable volume, else the primary
  volume, else none. Persisting the chosen id and the picker UI are out of scope here (#45).
- File access helpers used by torrent (save path) and player (file URI).

## Boundaries
- No knowledge of torrents beyond an id and a path. Removable media going away is a normal state, not a crash.

## Tests
JVM tests for layout/path logic over a JUnit `TemporaryFolder` as the volume root: directory paths,
creation, invalid ids, path-escape rejection, and `FileSpaceProvider` against the temp directory.
`VolumeSelectorTest` covers every branch of `VolumeSelector.select` with fake `VolumeInfo`s and a
fake `SpaceProvider`. `AndroidStorageVolumeProvider` has no JVM unit test -- it needs a real
`Context`/`StorageManager` -- CI only checks that it compiles; behaviour on a real TV with a USB
drive is a manual check.
