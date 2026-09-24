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
- File access helpers used by torrent (save path) and player (file URI).

## Boundaries
- No knowledge of torrents beyond an id and a path. Removable media going away is a normal state, not a crash.

## Tests
JVM tests for layout/path logic over a JUnit `TemporaryFolder` as the volume root: directory paths,
creation, invalid ids, path-escape rejection, and `FileSpaceProvider` against the temp directory.
